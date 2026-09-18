package com.ditto.whiteboard.transport

import com.ditto.whiteboard.data.BoardSession
import com.ditto.whiteboard.data.TEST_BOARD_SESSION_MESSAGES
import com.ditto.whiteboard.domain.BOARD_WIDTH
import com.ditto.whiteboard.domain.BoardObject
import com.ditto.whiteboard.domain.DrawingTool
import com.ditto.whiteboard.domain.LivePreview
import com.ditto.whiteboard.domain.LogicalPoint
import com.ditto.whiteboard.domain.ObjectId
import com.ditto.whiteboard.domain.OperationId
import com.ditto.whiteboard.domain.OperationStamp
import com.ditto.whiteboard.domain.UserProfile
import com.ditto.whiteboard.domain.WHITEBOARD_PALETTE
import com.ditto.whiteboard.domain.BoardOperation
import kotlin.random.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TenPeerConvergenceTest {
  @Test
  fun tenPeersConvergeAfterReorderingAndDuplicateReliableDelivery() = runTest {
    val network = FakeNetwork()
    val transports = (0 until 10).map { FakeTransport("peer-${it.toString().padStart(2, '0')}", network) }
    val sessions = transports.map {
      BoardSession(it, backgroundScope, TEST_BOARD_SESSION_MESSAGES)
    }
    sessions.forEachIndexed { index, session ->
      session.start("Artist $index", WHITEBOARD_PALETTE[index % WHITEBOARD_PALETTE.size])
    }
    runCurrent()

    repeat(120) { index ->
      val session = sessions[index % sessions.size]
      val x = (index * 37) % 1_900
      session.commit(
        tool = if (index % 3 == 0) DrawingTool.Pen else DrawingTool.Line,
        colorArgb = WHITEBOARD_PALETTE[index % WHITEBOARD_PALETTE.size],
        points = listOf(LogicalPoint(x, 20), LogicalPoint((x + 100).coerceAtMost(BOARD_WIDTH), 500)),
      )
    }
    sessions[3].clear()
    repeat(20) { index ->
      sessions[(index + 4) % sessions.size].commit(
        DrawingTool.Rectangle,
        0xFF007A3D.toInt(),
        listOf(LogicalPoint(index * 20, 100), LogicalPoint(index * 20 + 80, 220)),
      )
    }
    runCurrent()

    network.flushReliable(seed = 42, duplicateCount = 3)
    runCurrent()

    val states = sessions.map { it.boardState.value }
    states.drop(1).forEach { assertEquals(states.first(), it) }
    assertEquals(151, states.first().operations.size)
  }

  /**
   * Pins the fix for the "snapshot merge doesn't advance the local clock" regression: a late joiner
   * hydrates a board whose history includes a high-lamport Clear, then draws locally. The local
   * stroke must be stamped strictly above the clear watermark and appear, rather than being folded
   * behind the watermark and silently suppressed.
   */
  @Test
  fun lateJoinerDrawsAboveSnapshotClearWatermark() = runTest {
    val network = FakeNetwork()
    val transport = FakeTransport("peer-late", network)
    val session = BoardSession(transport, backgroundScope, TEST_BOARD_SESSION_MESSAGES)
    session.start("Late Joiner", WHITEBOARD_PALETTE[1])
    runCurrent()

    // A snapshot from an established peer: an early stroke, a Clear at lamport 100, then a stroke
    // drawn after the clear — exactly the history a genuine late-join snapshot carries.
    val remote = "peer-old"
    val clearStamp = OperationStamp(lamport = 100, peerKey = remote, senderSequence = 2)
    val preClearStamp = OperationStamp(lamport = 50, peerKey = remote, senderSequence = 1)
    val postClearStamp = OperationStamp(lamport = 120, peerKey = remote, senderSequence = 3)
    val history = listOf(
      BoardOperation.Commit(
        OperationId(remote, 1),
        preClearStamp,
        BoardObject.Line(ObjectId(OperationId(remote, 1)), preClearStamp, WHITEBOARD_PALETTE[2], start = LogicalPoint(0, 0), end = LogicalPoint(10, 10)),
      ),
      BoardOperation.Clear(OperationId(remote, 2), clearStamp),
      BoardOperation.Commit(
        OperationId(remote, 3),
        postClearStamp,
        BoardObject.Line(ObjectId(OperationId(remote, 3)), postClearStamp, WHITEBOARD_PALETTE[1], start = LogicalPoint(5, 5), end = LogicalPoint(20, 20)),
      ),
    )
    transport.deliver(TransportEvent.SnapshotMerged(history))
    runCurrent()

    // Only the post-clear stroke survives the watermark.
    val objectsAfterSnapshot = session.boardState.value.objects.size
    assertEquals(1, objectsAfterSnapshot)

    session.commit(DrawingTool.Pen, WHITEBOARD_PALETTE[3], listOf(LogicalPoint(100, 100), LogicalPoint(200, 200)))
    runCurrent()

    val state = session.boardState.value
    // The local stroke must appear alongside the surviving remote stroke.
    assertEquals(objectsAfterSnapshot + 1, state.objects.size)
    // And it must sort strictly above the clear watermark — proving the clock advanced past lamport 100.
    val localStroke = state.objects.values.single { it.stamp.peerKey == "peer-late" }
    assertTrue(localStroke.stamp > clearStamp)
    assertEquals(clearStamp, state.clearWatermark)
  }
}

private class FakeNetwork {
  private val transports = mutableMapOf<String, FakeTransport>()
  private val pending = mutableListOf<Pair<String, BoardOperation>>()

  fun attach(transport: FakeTransport) {
    transports[transport.localPeerKey] = transport
  }

  fun enqueue(sender: String, operation: BoardOperation) {
    synchronized(pending) { pending += sender to operation }
  }

  fun flushReliable(seed: Int, duplicateCount: Int) {
    val deliveries = synchronized(pending) {
      pending.flatMap { entry -> List(duplicateCount) { entry } }.also { pending.clear() }
    }.shuffled(Random(seed))
    deliveries.forEach { (sender, operation) ->
      transports.filterKeys { it != sender }.values.forEach { transport ->
        transport.deliver(TransportEvent.ReliableOperationReceived(operation))
      }
    }
  }
}

private class FakeTransport(
  override val localPeerKey: String,
  private val network: FakeNetwork,
) : WhiteboardTransport {
  private val mutableEvents = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 10_000)
  override val events = mutableEvents.asSharedFlow()
  private val mutableDiagnostics = MutableStateFlow(TransportDiagnostics(localPeerKey = localPeerKey))
  override val diagnostics = mutableDiagnostics.asStateFlow()

  init { network.attach(this) }

  override suspend fun start(profile: UserProfile) {
    mutableDiagnostics.value = mutableDiagnostics.value.copy(running = true)
  }

  override suspend fun sendReliable(operation: BoardOperation): Boolean {
    network.enqueue(localPeerKey, operation)
    return true
  }
  override fun sendLive(preview: LivePreview) = Unit
  fun deliver(event: TransportEvent) { check(mutableEvents.tryEmit(event)) }
  override fun close() = Unit
}
