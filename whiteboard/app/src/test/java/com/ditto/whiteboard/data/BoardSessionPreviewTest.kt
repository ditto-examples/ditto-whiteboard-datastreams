package com.ditto.whiteboard.data

import com.ditto.whiteboard.domain.DrawingTool
import com.ditto.whiteboard.domain.BoardObject
import com.ditto.whiteboard.domain.BoardOperation
import com.ditto.whiteboard.domain.LivePreview
import com.ditto.whiteboard.domain.LogicalPoint
import com.ditto.whiteboard.domain.ObjectId
import com.ditto.whiteboard.domain.OperationId
import com.ditto.whiteboard.domain.OperationStamp
import com.ditto.whiteboard.domain.UserProfile
import com.ditto.whiteboard.domain.WHITEBOARD_PALETTE
import com.ditto.whiteboard.transport.TransportDiagnostics
import com.ditto.whiteboard.transport.TransportEvent
import com.ditto.whiteboard.transport.WhiteboardTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BoardSessionPreviewTest {
  /**
   * The local in-progress stroke is drawn by the canvas from its own `activePoints`; if the local
   * peer also appeared in the previews map the canvas would draw it a second time (at the remote
   * alpha) and leave a TTL ghost when the stroke was abandoned. preview() must therefore never add
   * the local peer to the map, even though it still hands the preview to the transport for peers.
   */
  @Test
  fun localPreviewIsSentButNotAddedToPreviewsMap() = runTest {
    val transport = FakeTransport("peer-local")
    val session = BoardSession(transport, backgroundScope, TEST_BOARD_SESSION_MESSAGES)
    session.start("Local Artist", WHITEBOARD_PALETTE[1])
    runCurrent()

    session.preview(
      "gesture-local",
      DrawingTool.Pen,
      0xFF0057B8.toInt(),
      listOf(LogicalPoint(10, 10), LogicalPoint(20, 20)),
    )
    runCurrent()

    assertFalse("local peer must not be in the previews map", session.previews.value.containsKey("peer-local"))
    assertTrue("local preview must still be broadcast to peers", transport.sentLive.isNotEmpty())
    assertEquals("peer-local", transport.sentLive.last().peerKey)
  }

  /** Remote previews must still flow into the map so peers' in-progress strokes render. */
  @Test
  fun remotePreviewIsAddedToPreviewsMap() = runTest {
    val transport = FakeTransport("peer-local")
    val session = BoardSession(transport, backgroundScope, TEST_BOARD_SESSION_MESSAGES)
    session.start("Local Artist", WHITEBOARD_PALETTE[1])
    runCurrent()

    val remote = LivePreview(
      peerKey = "peer-remote",
      tool = DrawingTool.Pen,
      colorArgb = WHITEBOARD_PALETTE[2],
      points = listOf(LogicalPoint(30, 30), LogicalPoint(40, 40)),
      expiresAtMillis = System.currentTimeMillis() + 5_000,
      gestureId = "gesture-remote",
      frameSequence = 1,
      senderSessionId = "session-remote",
    )
    transport.deliver(TransportEvent.LivePreviewReceived(remote))
    runCurrent()

    assertTrue("remote peer must be in the previews map", session.previews.value.containsKey("peer-remote"))
    assertFalse(session.previews.value.containsKey("peer-local"))
  }

  @Test
  fun slidingPreviewSegmentsMergeTheirLargestOverlap() = runTest {
    val transport = FakeTransport("peer-local")
    val session = BoardSession(transport, backgroundScope, TEST_BOARD_SESSION_MESSAGES)
    session.start("Local Artist", WHITEBOARD_PALETTE[1])

    transport.deliver(TransportEvent.LivePreviewReceived(remotePreview(1, points = points(1, 2, 3))))
    transport.deliver(TransportEvent.LivePreviewReceived(remotePreview(2, points = points(2, 3, 4))))
    runCurrent()

    assertEquals(points(1, 2, 3, 4), session.previews.value.getValue("peer-remote").points)
  }

  @Test
  fun duplicateAndReorderedPreviewFramesCannotRewindTheStroke() = runTest {
    val transport = FakeTransport("peer-local")
    val session = BoardSession(transport, backgroundScope, TEST_BOARD_SESSION_MESSAGES)
    session.start("Local Artist", WHITEBOARD_PALETTE[1])

    transport.deliver(TransportEvent.LivePreviewReceived(remotePreview(2, points = points(2, 3))))
    transport.deliver(TransportEvent.LivePreviewReceived(remotePreview(1, points = points(1, 2))))
    transport.deliver(TransportEvent.LivePreviewReceived(remotePreview(2, points = points(9))))
    runCurrent()

    assertEquals(points(2, 3), session.previews.value.getValue("peer-remote").points)
  }

  @Test
  fun newGestureReplacesRatherThanJoinsThePriorPreview() = runTest {
    val transport = FakeTransport("peer-local")
    val session = BoardSession(transport, backgroundScope, TEST_BOARD_SESSION_MESSAGES)
    session.start("Local Artist", WHITEBOARD_PALETTE[1])

    transport.deliver(TransportEvent.LivePreviewReceived(remotePreview(1, points = points(1, 2))))
    transport.deliver(
      TransportEvent.LivePreviewReceived(
        remotePreview(2, gesture = "gesture-new", points = points(8, 9)),
      ),
    )
    runCurrent()

    val preview = session.previews.value.getValue("peer-remote")
    assertEquals("gesture-new", preview.gestureId)
    assertEquals(points(8, 9), preview.points)
  }

  @Test
  fun reliableCommitRetiresItsPreviewAndLateLiveFrameCannotResurrectIt() = runTest {
    val transport = FakeTransport("peer-local")
    val session = BoardSession(transport, backgroundScope, TEST_BOARD_SESSION_MESSAGES)
    session.start("Local Artist", WHITEBOARD_PALETTE[1])
    val operation = remoteCommit("gesture-remote")

    transport.deliver(TransportEvent.LivePreviewReceived(remotePreview(1)))
    transport.deliver(TransportEvent.ReliableOperationReceived(operation))
    transport.deliver(TransportEvent.LivePreviewReceived(remotePreview(2)))
    runCurrent()

    assertFalse(session.previews.value.containsKey("peer-remote"))
    assertEquals(operation, session.boardState.value.operations[operation.id])
  }

  @Test
  fun restartedSenderSessionAdvancesButFramesFromRetiredSessionStayIgnored() = runTest {
    val transport = FakeTransport("peer-local")
    val session = BoardSession(transport, backgroundScope, TEST_BOARD_SESSION_MESSAGES)
    session.start("Local Artist", WHITEBOARD_PALETTE[1])

    transport.deliver(TransportEvent.LivePreviewReceived(remotePreview(9, session = "old")))
    transport.deliver(
      TransportEvent.LivePreviewReceived(
        remotePreview(1, session = "new", gesture = "new", points = points(7)),
      ),
    )
    transport.deliver(
      TransportEvent.LivePreviewReceived(
        remotePreview(10, session = "old", gesture = "old-late", points = points(99)),
      ),
    )
    runCurrent()

    val preview = session.previews.value.getValue("peer-remote")
    assertEquals("new", preview.senderSessionId)
    assertEquals(points(7), preview.points)
  }

  @Test
  fun operationIsNotAppliedUntilTransportCommitsItsAuthoritativeLog() = runTest {
    val transport = FakeTransport("peer-local")
    val session = BoardSession(transport, backgroundScope, TEST_BOARD_SESSION_MESSAGES)
    session.start("Local Artist", WHITEBOARD_PALETTE[1])
    val operation = remoteCommit("gesture")
    val prepared = CompletableDeferred<Boolean>()
    val committed = CompletableDeferred<Boolean>()
    val applied = CompletableDeferred<Boolean>()

    transport.deliver(
      TransportEvent.ReliableOperationReceived(operation, prepared, committed, applied),
    )
    runCurrent()
    assertTrue(prepared.await())
    assertFalse(session.boardState.value.operations.containsKey(operation.id))

    committed.complete(false)
    runCurrent()
    assertFalse(applied.await())
    assertFalse(session.boardState.value.operations.containsKey(operation.id))
  }

  @Test
  fun degenerateAreaShapesDoNotConsumeOperationCapacity() = runTest {
    val transport = FakeTransport("peer-local")
    val session = BoardSession(transport, backgroundScope, TEST_BOARD_SESSION_MESSAGES)
    session.start("Local Artist", WHITEBOARD_PALETTE[1])
    runCurrent()
    val initialCount = session.boardState.value.operations.size

    session.commit(
      DrawingTool.Rectangle,
      WHITEBOARD_PALETTE[1],
      listOf(LogicalPoint(10, 10), LogicalPoint(10, 50)),
    )
    session.commit(
      DrawingTool.Ellipse,
      WHITEBOARD_PALETTE[1],
      listOf(LogicalPoint(20, 20), LogicalPoint(40, 20)),
    )
    runCurrent()

    assertEquals(initialCount, session.boardState.value.operations.size)
  }

  private fun points(vararg coordinates: Int): List<LogicalPoint> =
    coordinates.map { LogicalPoint(it, it) }

  private fun remotePreview(
    sequence: Long,
    session: String = "session-remote",
    gesture: String = "gesture-remote",
    points: List<LogicalPoint> = points(1, 2),
  ): LivePreview = LivePreview(
    peerKey = "peer-remote",
    tool = DrawingTool.Pen,
      colorArgb = WHITEBOARD_PALETTE[2],
    points = points,
    expiresAtMillis = System.currentTimeMillis() + 5_000,
    gestureId = gesture,
    frameSequence = sequence,
    senderSessionId = session,
  )

  private fun remoteCommit(gesture: String): BoardOperation.Commit {
    val id = OperationId("peer-remote", 1)
    val stamp = OperationStamp(2, "peer-remote", 1)
    return BoardOperation.Commit(
      id = id,
      stamp = stamp,
      boardObject = BoardObject.Line(
        id = ObjectId(id),
        stamp = stamp,
        colorArgb = WHITEBOARD_PALETTE[2],
        start = LogicalPoint(1, 1),
        end = LogicalPoint(2, 2),
      ),
      gestureId = gesture,
    )
  }
}

private class FakeTransport(
  override val localPeerKey: String,
) : WhiteboardTransport {
  private val mutableEvents = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 64)
  override val events = mutableEvents.asSharedFlow()
  private val mutableDiagnostics = MutableStateFlow(TransportDiagnostics(localPeerKey = localPeerKey))
  override val diagnostics = mutableDiagnostics.asStateFlow()
  val sentLive = mutableListOf<LivePreview>()

  override suspend fun start(profile: UserProfile) {
    mutableDiagnostics.value = mutableDiagnostics.value.copy(running = true)
  }

  override suspend fun sendReliable(
    operation: com.ditto.whiteboard.domain.BoardOperation,
  ): Boolean = true
  override fun sendLive(preview: LivePreview) { sentLive += preview }
  fun deliver(event: TransportEvent) { check(mutableEvents.tryEmit(event)) }
  override fun close() = Unit
}
