package com.ditto.whiteboard.data

import com.ditto.whiteboard.domain.BoardGeometry
import com.ditto.whiteboard.domain.BoardObject
import com.ditto.whiteboard.domain.BoardOperation
import com.ditto.whiteboard.domain.BoardReducer
import com.ditto.whiteboard.domain.BoardState
import com.ditto.whiteboard.domain.DrawingTool
import com.ditto.whiteboard.domain.LivePreview
import com.ditto.whiteboard.domain.LogicalPoint
import com.ditto.whiteboard.domain.ObjectId
import com.ditto.whiteboard.domain.OperationClock
import com.ditto.whiteboard.domain.UserProfile
import com.ditto.whiteboard.transport.TransportEvent
import com.ditto.whiteboard.transport.WhiteboardTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Cap on the number of points retained for a single live preview, to bound memory for a fast peer. */
private const val MAX_PREVIEW_POINTS = 256

/** How long a live preview is shown before it expires if no follow-up arrives (ms). */
private const val PREVIEW_TTL_MILLIS = 2_000L

/** How often expired live previews are swept out of the cache (ms). */
private const val PREVIEW_SWEEP_INTERVAL_MILLIS = 500L

/**
 * Coordinates the local board with the [WhiteboardTransport]: assigns Lamport-ordered stamps to
 * local operations, folds every operation (local and remote) through [BoardReducer] into the shared
 * [boardState], de-duplicates by operation id, and maintains a short-lived cache of remote drawing
 * [previews].
 *
 * State is exposed as [StateFlow]s and mutated exclusively through [MutableStateFlow.update] so that
 * concurrent writes — local edits on the main thread and inbound Data Streams events on a background
 * dispatcher — compose atomically instead of racing (a plain `value = f(value)` could lose updates).
 */
class BoardSession(
  private val transport: WhiteboardTransport,
  private val scope: CoroutineScope,
) : AutoCloseable {
  val localPeerKey: String get() = transport.localPeerKey
  private val clock = OperationClock(localPeerKey)
  private val mutableBoardState = MutableStateFlow(BoardState())
  val boardState: StateFlow<BoardState> = mutableBoardState.asStateFlow()
  private val mutablePreviews = MutableStateFlow<Map<String, LivePreview>>(emptyMap())
  val previews: StateFlow<Map<String, LivePreview>> = mutablePreviews.asStateFlow()
  val diagnostics = transport.diagnostics.stateIn(scope, SharingStarted.Eagerly, transport.diagnostics.value)
  val requiredPermissions: List<String> get() = transport.requiredPermissions
  private var startedProfile: UserProfile? = null
  private var transportJob: Job? = null

  init {
    scope.launch {
      while (isActive) {
        delay(PREVIEW_SWEEP_INTERVAL_MILLIS)
        val now = System.currentTimeMillis()
        mutablePreviews.update { previews -> previews.filterValues { it.expiresAtMillis > now } }
      }
    }
  }

  suspend fun start(displayName: String, colorArgb: Int) {
    val profile = UserProfile(localPeerKey, displayName, colorArgb)
    if (startedProfile == profile) return
    if (transportJob == null) {
      transportJob = scope.launch {
        transport.events.collect { event ->
          when (event) {
            is TransportEvent.ReliableOperationReceived -> receive(event.operation)
            is TransportEvent.LivePreviewReceived -> {
              if (event.preview.peerKey != localPeerKey) {
                val now = System.currentTimeMillis()
                mutablePreviews.update { previews ->
                  val prior = previews[event.preview.peerKey]
                  val merged = if (
                    prior != null &&
                    prior.tool == event.preview.tool &&
                    prior.colorArgb == event.preview.colorArgb &&
                    prior.expiresAtMillis > now
                  ) {
                    val combined = if (prior.points.lastOrNull() == event.preview.points.firstOrNull()) {
                      prior.points + event.preview.points.drop(1)
                    } else {
                      prior.points + event.preview.points
                    }
                    event.preview.copy(points = combined.takeLast(MAX_PREVIEW_POINTS))
                  } else event.preview
                  previews + (event.preview.peerKey to merged)
                }
              }
            }
            is TransportEvent.SnapshotMerged -> {
              // Advance the local clock past every merged stamp so the next local operation is
              // stamped strictly above all snapshot history. receive() is bypassed on this branch,
              // so without this a late joiner's next stamp can sort below an existing Clear
              // watermark (or all prior objects) and be silently suppressed.
              event.operations.forEach { clock.observe(it.stamp) }
              mutableBoardState.update { BoardReducer.merge(it, event.operations) }
            }
            is TransportEvent.IncompatiblePeer -> Unit
          }
        }
      }
    }
    transport.start(profile)
    startedProfile = profile
    submit { id, stamp -> BoardOperation.ProfileUpdate(id, stamp, profile) }
  }

  fun preview(tool: DrawingTool, colorArgb: Int, points: List<LogicalPoint>) {
    if (points.isEmpty()) return
    val preview = LivePreview(
      peerKey = localPeerKey,
      tool = tool,
      colorArgb = colorArgb,
      points = points.map(LogicalPoint::clamped),
      expiresAtMillis = System.currentTimeMillis() + PREVIEW_TTL_MILLIS,
    )
    // The local in-progress stroke is rendered by the canvas from its own `activePoints`, so it must
    // not also live in the previews map: doing so double-drew the local stroke (once at the local
    // alpha, once at the remote alpha) and left a ~2s TTL ghost when a stroke was abandoned via a
    // two-finger pan. Inbound loopback previews are already filtered by peerKey != localPeerKey, so
    // the local peer is intentionally absent from this map.
    transport.sendLive(preview)
  }

  fun commit(tool: DrawingTool, colorArgb: Int, points: List<LogicalPoint>, text: String = "") {
    if (points.isEmpty()) return
    if (tool == DrawingTool.Eraser) {
      submit { id, stamp -> BoardOperation.Erase(id, stamp, points.map(LogicalPoint::clamped)) }
      mutablePreviews.update { it - localPeerKey }
      return
    }
    submit { id, stamp ->
      val objectId = ObjectId(id)
      val clamped = points.map(LogicalPoint::clamped)
      val boardObject = when (tool) {
        DrawingTool.Pen -> BoardObject.Freehand(objectId, stamp, colorArgb, points = BoardGeometry.simplify(clamped))
        DrawingTool.Line -> BoardObject.Line(objectId, stamp, colorArgb, start = clamped.first(), end = clamped.last())
        DrawingTool.Rectangle -> BoardObject.Rectangle(objectId, stamp, colorArgb, start = clamped.first(), end = clamped.last())
        DrawingTool.Ellipse -> BoardObject.Ellipse(objectId, stamp, colorArgb, start = clamped.first(), end = clamped.last())
        DrawingTool.Text -> BoardObject.Text(objectId, stamp, colorArgb, anchor = clamped.first(), text = text.take(200))
        DrawingTool.Eraser -> error("Handled above")
      }
      BoardOperation.Commit(id, stamp, boardObject)
    }
    mutablePreviews.update { it - localPeerKey }
  }

  fun clear() = submit { id, stamp -> BoardOperation.Clear(id, stamp) }

  fun resolvePermissions(allGranted: Boolean) = transport.resolvePermissions(allGranted)

  private fun submit(create: (com.ditto.whiteboard.domain.OperationId, com.ditto.whiteboard.domain.OperationStamp) -> BoardOperation) {
    val (id, stamp) = clock.next()
    val operation = create(id, stamp)
    receive(operation)
    scope.launch { transport.sendReliable(operation) }
  }

  private fun receive(operation: BoardOperation) {
    // Apply-if-absent atomically. The update block is side-effect free and may run more than once
    // under contention; `applied` reflects the invocation whose compare-and-set won.
    var applied = false
    mutableBoardState.update { state ->
      if (state.operations.containsKey(operation.id)) {
        applied = false
        state
      } else {
        applied = true
        BoardReducer.apply(state, operation)
      }
    }
    if (!applied) return
    clock.observe(operation.stamp)
    mutablePreviews.update { it - operation.id.senderPeerKey }
  }

  override fun close() {
    transportJob?.cancel()
    transport.close()
  }
}
