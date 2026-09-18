package com.ditto.whiteboard.data

import com.ditto.whiteboard.domain.BoardGeometry
import com.ditto.whiteboard.domain.BoardObject
import com.ditto.whiteboard.domain.BoardOperation
import com.ditto.whiteboard.domain.BoardReducer
import com.ditto.whiteboard.domain.BoardState
import com.ditto.whiteboard.domain.DrawingTool
import com.ditto.whiteboard.domain.LivePreview
import com.ditto.whiteboard.domain.LogicalPoint
import com.ditto.whiteboard.domain.isApprovedWhiteboardColor
import com.ditto.whiteboard.util.runSuspendCatchingPreservingCancellation
import com.ditto.whiteboard.util.runCatchingException
import com.ditto.whiteboard.domain.MAX_OPERATION_COUNTER
import com.ditto.whiteboard.domain.MAX_RENDERED_BOARD_OBJECTS
import com.ditto.whiteboard.domain.ObjectId
import com.ditto.whiteboard.domain.OperationClock
import com.ditto.whiteboard.domain.OperationId
import com.ditto.whiteboard.domain.UserProfile
import com.ditto.whiteboard.transport.TransportEvent
import com.ditto.whiteboard.transport.WhiteboardTransport
import com.ditto.whiteboard.protocol.MAX_OPERATION_POINTS
import com.ditto.whiteboard.protocol.MAX_ERASER_POINTS
import com.ditto.whiteboard.protocol.MAX_SESSION_LAMPORT
import com.ditto.whiteboard.protocol.WhiteboardProtocol
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Cap on the number of points retained for a single live preview, to bound memory for a fast peer. */
private const val MAX_PREVIEW_POINTS = 128

/** How long a live preview is shown before it expires if no follow-up arrives (ms). */
private const val PREVIEW_TTL_MILLIS = 2_000L

/** How often expired live previews are swept out of the cache (ms). */
private const val PREVIEW_SWEEP_INTERVAL_MILLIS = 500L
private const val LIVE_PREVIEW_SEGMENT_POINTS = 32

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
  private val messages: BoardSessionMessages,
  private val reserveOperationClock: suspend () -> OperationClockReservation = {
    OperationClockReservation(0L, 0L, MAX_OPERATION_COUNTER)
  },
  private val reserveLamportAfter: suspend (Long) -> Long = { MAX_OPERATION_COUNTER },
) : AutoCloseable {
  val localPeerKey: String get() = transport.localPeerKey
  private var clock: OperationClock? = null
  private var lamportCeiling: Long = MAX_OPERATION_COUNTER
  private val startMutex = Mutex()
  private val mutableBoardState = MutableStateFlow(BoardState())
  val boardState: StateFlow<BoardState> = mutableBoardState.asStateFlow()
  private val mutablePreviews = MutableStateFlow<Map<String, LivePreview>>(emptyMap())
  val previews: StateFlow<Map<String, LivePreview>> = mutablePreviews.asStateFlow()
  private val mutableError = MutableStateFlow<String?>(null)
  val error: StateFlow<String?> = mutableError.asStateFlow()
  val diagnostics = transport.diagnostics.stateIn(scope, SharingStarted.Eagerly, transport.diagnostics.value)
  val requiredPermissions: List<String> get() = transport.requiredPermissions
  private var startedProfile: UserProfile? = null
  private var transportJob: Job? = null
  private var profileJob: Job? = null
  private val previewLock = Any()
  private val lastLiveSequence = mutableMapOf<String, Long>()
  private val currentLiveSession = mutableMapOf<String, String>()
  private val retiredLiveSessions = mutableMapOf<String, ArrayDeque<String>>()
  private val completedGestures = mutableMapOf<String, ArrayDeque<String>>()

  init {
    scope.launch {
      while (isActive) {
        delay(PREVIEW_SWEEP_INTERVAL_MILLIS)
        val now = System.currentTimeMillis()
        mutablePreviews.update { previews -> previews.filterValues { it.expiresAtMillis > now } }
        val retainedPeers = transport.diagnostics.value.peers.keys + mutablePreviews.value.keys
        synchronized(previewLock) {
          lastLiveSequence.keys.retainAll(retainedPeers)
          currentLiveSession.keys.retainAll(retainedPeers)
          retiredLiveSessions.keys.retainAll(retainedPeers)
          completedGestures.keys.retainAll(retainedPeers)
        }
      }
    }
    scope.launch {
      diagnostics.collect { current ->
        if (
          current.editingReady &&
          mutableError.value in setOf(messages.syncFinishing, messages.sessionStarting)
        ) {
          mutableError.value = null
        }
      }
    }
  }

  suspend fun start(displayName: String, colorArgb: Int) = startMutex.withLock {
    require(isApprovedWhiteboardColor(colorArgb)) { "Drawing color must be in the approved palette" }
    val profile = UserProfile(localPeerKey, displayName, colorArgb)
    if (startedProfile == profile) return@withLock
    val activeClock = clock ?: reserveOperationClock().let { reservation ->
      lamportCeiling = reservation.lamportCeiling
      OperationClock(
        peerKey = localPeerKey,
        initialSenderSequence = reservation.firstSenderSequence,
        initialLamport = reservation.initialLamport,
      )
    }.also { clock = it }
    if (transportJob == null) {
      transportJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        transport.events.collect { event ->
          // Guard every event. Without this, one unexpected failure while applying a remote
          // operation completes this collector for good: local editing keeps working while every
          // further remote edit is silently ignored, with no repair path because the transport's
          // digest already counts the operation. The transport applies the same discipline to its
          // own reliable-ingress worker. Cancellation still propagates so shutdown stays prompt.
          try {
            when (event) {
              is TransportEvent.ReliableOperationReceived -> {
                var applied = false
                try {
                  val prepared = prepareOperation(activeClock, event.operation)
                  event.prepared?.complete(prepared)
                  if (!prepared) return@collect
                  if (event.committed?.await() == false) return@collect
                  val changed = applyPreparedOperation(event.operation)
                  applied = changed || event.operation.id in mutableBoardState.value.operations
                } finally {
                  event.prepared?.complete(false)
                  event.applied?.complete(applied)
                }
              }
              is TransportEvent.LivePreviewReceived ->
                if (event.preview.peerKey != localPeerKey) receivePreview(event.preview)
              is TransportEvent.SnapshotMerged -> {
                var applied = false
                try {
                  val maximumStamp = event.operations.maxOfOrNull(BoardOperation::stamp)
                  if (
                    maximumStamp != null &&
                    !prepareClockFor(activeClock, maximumStamp)
                  ) {
                    event.prepared?.complete(false)
                    return@collect
                  }
                  event.prepared?.complete(true)
                  if (event.committed?.await() == false) return@collect
                  // Advance the clock before exposing transport readiness. The transport awaits the
                  // event acknowledgement, preventing local edits from racing below snapshot history.
                  mutableBoardState.update { BoardReducer.merge(it, event.operations) }
                  event.operations.filterIsInstance<BoardOperation.Commit>().forEach {
                    completeGesture(it.id.senderPeerKey, it.gestureId)
                  }
                  event.operations.filterIsInstance<BoardOperation.Erase>().forEach {
                    completeGesture(it.id.senderPeerKey, it.gestureId)
                  }
                  applied = true
                } finally {
                  event.prepared?.complete(false)
                  event.applied?.complete(applied)
                }
              }
              is TransportEvent.IncompatiblePeer -> Unit
            }
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (error: Exception) {
            mutableError.value = messages.remoteUpdateFailed
          }
        }
      }
    }
    transport.start(profile)
    startedProfile = profile
    scheduleProfileUpdate(profile)
  }

  fun preview(
    gestureId: String,
    tool: DrawingTool,
    colorArgb: Int,
    points: List<LogicalPoint>,
  ) {
    if (points.isEmpty()) return
    if (!isApprovedWhiteboardColor(colorArgb)) return
    if (!transport.diagnostics.value.editingReady) return
    val preview = LivePreview(
      peerKey = localPeerKey,
      tool = tool,
      colorArgb = colorArgb,
      points = points.takeLast(LIVE_PREVIEW_SEGMENT_POINTS).map(LogicalPoint::clamped),
      expiresAtMillis = System.currentTimeMillis() + PREVIEW_TTL_MILLIS,
      gestureId = gestureId,
    )
    // The local in-progress stroke is rendered by the canvas from its own `activePoints`, so it must
    // not also live in the previews map: doing so double-drew the local stroke (once at the local
    // alpha, once at the remote alpha) and left a ~2s TTL ghost when a stroke was abandoned via a
    // two-finger pan. Inbound loopback previews are already filtered by peerKey != localPeerKey, so
    // the local peer is intentionally absent from this map.
    transport.sendLive(preview)
  }

  fun commit(
    tool: DrawingTool,
    colorArgb: Int,
    points: List<LogicalPoint>,
    text: String = "",
    gestureId: String = UUID.randomUUID().toString(),
  ) {
    if (points.isEmpty()) return
    if (!ensureEditingReady()) return
    if (!isApprovedWhiteboardColor(colorArgb)) return
    if (tool == DrawingTool.Text && text.isBlank()) {
      mutableError.value = messages.textCannotBeBlank
      return
    }
    val maximumPoints = if (tool == DrawingTool.Eraser) MAX_ERASER_POINTS else MAX_OPERATION_POINTS
    val boundedPoints = BoardGeometry.evenlySample(points, maximumPoints)
    if (tool == DrawingTool.Eraser) {
      submit { id, stamp ->
        BoardOperation.Erase(
          id,
          stamp,
          BoardGeometry.simplify(boundedPoints.map(LogicalPoint::clamped)),
          gestureId = gestureId,
        )
      }
      mutablePreviews.update { it - localPeerKey }
      return
    }
    val clampedPoints = boundedPoints.map(LogicalPoint::clamped)
    if (
      tool in setOf(DrawingTool.Rectangle, DrawingTool.Ellipse) &&
      (
        clampedPoints.first().x == clampedPoints.last().x ||
          clampedPoints.first().y == clampedPoints.last().y
        )
    ) {
      return
    }
    submit { id, stamp ->
      val objectId = ObjectId(id)
      val boardObject = when (tool) {
        DrawingTool.Pen -> BoardObject.Freehand(objectId, stamp, colorArgb, points = BoardGeometry.simplify(clampedPoints))
        DrawingTool.Line -> BoardObject.Line(objectId, stamp, colorArgb, start = clampedPoints.first(), end = clampedPoints.last())
        DrawingTool.Rectangle -> BoardObject.Rectangle(objectId, stamp, colorArgb, start = clampedPoints.first(), end = clampedPoints.last())
        DrawingTool.Ellipse -> BoardObject.Ellipse(objectId, stamp, colorArgb, start = clampedPoints.first(), end = clampedPoints.last())
        DrawingTool.Text -> BoardObject.Text(objectId, stamp, colorArgb, anchor = clampedPoints.first(), text = text.take(200))
        DrawingTool.Eraser -> error("Handled above")
      }
      BoardOperation.Commit(id, stamp, boardObject, gestureId)
    }
    mutablePreviews.update { it - localPeerKey }
  }

  fun clear() {
    if (!ensureEditingReady()) return
    submit { id, stamp -> BoardOperation.Clear(id, stamp) }
  }

  fun resolvePermissions(allGranted: Boolean) = transport.resolvePermissions(allGranted)
  fun setForeground(isForeground: Boolean) = transport.setForeground(isForeground)

  private fun ensureEditingReady(): Boolean {
    if (transport.diagnostics.value.editingReady) return true
    mutableError.value = messages.syncFinishing
    return false
  }

  private fun scheduleProfileUpdate(profile: UserProfile) {
    profileJob?.cancel()
    profileJob = scope.launch {
      transport.diagnostics.filter { it.editingReady }.first()
      if (startedProfile == profile) {
        submit { id, stamp -> BoardOperation.ProfileUpdate(id, stamp, profile) }
      }
    }
  }

  private fun submit(create: (OperationId, com.ditto.whiteboard.domain.OperationStamp) -> BoardOperation) {
    val activeClock = clock
    if (activeClock == null) {
      mutableError.value = messages.sessionStarting
      return
    }
    val next = runCatchingException {
      activeClock.next()
    }.getOrElse {
      mutableError.value = messages.operationClockExhausted
      return
    }
    val (id, stamp) = next
    // Creation can include freehand simplification and reducer geometry. Keep both off the caller's
    // (normally Compose main) thread; the preallocated stamp preserves user action order even when
    // background work completes out of order, and the reducer handles such delivery deterministically.
    scope.launch {
      val operation = create(id, stamp)
      if (operation.stamp.lamport > MAX_SESSION_LAMPORT) {
        mutableError.value = messages.logicalTimeLimit
        return@launch
      }
      val valid = runCatchingException { WhiteboardProtocol.validateOperation(operation) }.isSuccess
      if (!valid) {
        mutableError.value = messages.editOutsideSafetyLimits
        return@launch
      }
      if (!prepareOperation(activeClock, operation)) return@launch
      if (transport.sendReliable(operation)) {
        applyPreparedOperation(operation)
      } else {
        mutableError.value = messages.terminalResourceLimit
      }
    }
  }

  private suspend fun prepareOperation(
    activeClock: OperationClock,
    operation: BoardOperation,
  ): Boolean {
    if (operation.stamp.lamport > MAX_SESSION_LAMPORT) {
      mutableError.value = messages.peerLogicalTimeLimit
      return false
    }
    return prepareClockFor(activeClock, operation.stamp)
  }

  private fun applyPreparedOperation(operation: BoardOperation): Boolean {
    // Apply-if-absent atomically. The update block is side-effect free and may run more than once
    // under contention; `applied` reflects the invocation whose compare-and-set won.
    var applied = false
    mutableBoardState.update { state ->
      val nextState = BoardReducer.apply(state, operation)
      applied = nextState != state
      nextState
    }
    if (!applied) {
      return false
    }
    if (
      operation is BoardOperation.Commit &&
      operation.boardObject.id !in mutableBoardState.value.objects &&
      mutableBoardState.value.objects.size >= MAX_RENDERED_BOARD_OBJECTS
    ) {
      mutableError.value = messages.visibleObjectLimit
    }
    when (operation) {
      is BoardOperation.Commit ->
        completeGesture(operation.id.senderPeerKey, operation.gestureId)
      is BoardOperation.Erase ->
        completeGesture(operation.id.senderPeerKey, operation.gestureId)
      else -> mutablePreviews.update { it - operation.id.senderPeerKey }
    }
    return true
  }

  private suspend fun prepareClockFor(
    activeClock: OperationClock,
    stamp: com.ditto.whiteboard.domain.OperationStamp,
  ): Boolean {
    if (stamp.lamport >= lamportCeiling) {
      val reserved = runSuspendCatchingPreservingCancellation {
        reserveLamportAfter(stamp.lamport)
      }.getOrElse {
        mutableError.value = messages.clockReservationFailed
        return false
      }
      lamportCeiling = reserved
    }
    activeClock.observe(stamp)
    return true
  }

  private fun receivePreview(preview: LivePreview) {
    synchronized(previewLock) {
      val currentSession = currentLiveSession[preview.peerKey]
      if (preview.senderSessionId != currentSession) {
        if (retiredLiveSessions[preview.peerKey]?.contains(preview.senderSessionId) == true) return
        currentSession?.let { oldSession ->
          val retired = retiredLiveSessions.getOrPut(preview.peerKey) { ArrayDeque() }
          retired.addLast(oldSession)
          while (retired.size > 8) retired.removeFirst()
        }
        currentLiveSession[preview.peerKey] = preview.senderSessionId
        lastLiveSequence[preview.peerKey] = 0L
      }
      val previousSequence = lastLiveSequence[preview.peerKey] ?: 0L
      if (preview.frameSequence <= previousSequence) return
      lastLiveSequence[preview.peerKey] = preview.frameSequence
      if (completedGestures[preview.peerKey]?.contains(preview.gestureId) == true) return

      val now = System.currentTimeMillis()
      mutablePreviews.update { previews ->
        val prior = previews[preview.peerKey]
        val merged = if (
          prior != null &&
          prior.gestureId == preview.gestureId &&
          prior.expiresAtMillis > now
        ) {
          preview.copy(
            points = mergePreviewPoints(prior.points, preview.points).takeLast(MAX_PREVIEW_POINTS),
          )
        } else {
          preview
        }
        previews + (preview.peerKey to merged)
      }
    }
  }

  private fun completeGesture(peerKey: String, gestureId: String) {
    synchronized(previewLock) {
      val recent = completedGestures.getOrPut(peerKey) { ArrayDeque() }
      if (gestureId !in recent) recent.addLast(gestureId)
      while (recent.size > 64) recent.removeFirst()
      mutablePreviews.update { previews ->
        if (previews[peerKey]?.gestureId == gestureId) previews - peerKey else previews
      }
    }
  }

  private fun mergePreviewPoints(
    previous: List<LogicalPoint>,
    incoming: List<LogicalPoint>,
  ): List<LogicalPoint> {
    val maximumOverlap = minOf(previous.size, incoming.size)
    val overlap = (maximumOverlap downTo 1).firstOrNull { count ->
      previous.subList(previous.size - count, previous.size) == incoming.subList(0, count)
    } ?: 0
    return previous + incoming.drop(overlap)
  }

  override fun close() {
    profileJob?.cancel()
    transportJob?.cancel()
    transport.close()
  }
}
