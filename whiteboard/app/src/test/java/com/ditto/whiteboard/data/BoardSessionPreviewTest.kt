package com.ditto.whiteboard.data

import com.ditto.whiteboard.domain.DrawingTool
import com.ditto.whiteboard.domain.LivePreview
import com.ditto.whiteboard.domain.LogicalPoint
import com.ditto.whiteboard.domain.UserProfile
import com.ditto.whiteboard.transport.TransportDiagnostics
import com.ditto.whiteboard.transport.TransportEvent
import com.ditto.whiteboard.transport.WhiteboardTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    val session = BoardSession(transport, backgroundScope)
    session.start("Local Artist", 0xFF112233.toInt())
    runCurrent()

    session.preview(DrawingTool.Pen, 0xFF0057B8.toInt(), listOf(LogicalPoint(10, 10), LogicalPoint(20, 20)))
    runCurrent()

    assertFalse("local peer must not be in the previews map", session.previews.value.containsKey("peer-local"))
    assertTrue("local preview must still be broadcast to peers", transport.sentLive.isNotEmpty())
    assertEquals("peer-local", transport.sentLive.last().peerKey)
  }

  /** Remote previews must still flow into the map so peers' in-progress strokes render. */
  @Test
  fun remotePreviewIsAddedToPreviewsMap() = runTest {
    val transport = FakeTransport("peer-local")
    val session = BoardSession(transport, backgroundScope)
    session.start("Local Artist", 0xFF112233.toInt())
    runCurrent()

    val remote = LivePreview(
      peerKey = "peer-remote",
      tool = DrawingTool.Pen,
      colorArgb = 0xFF00FF00.toInt(),
      points = listOf(LogicalPoint(30, 30), LogicalPoint(40, 40)),
      expiresAtMillis = System.currentTimeMillis() + 5_000,
    )
    transport.deliver(TransportEvent.LivePreviewReceived(remote))
    runCurrent()

    assertTrue("remote peer must be in the previews map", session.previews.value.containsKey("peer-remote"))
    assertFalse(session.previews.value.containsKey("peer-local"))
  }
}

private class FakeTransport(
  override val localPeerKey: String,
) : WhiteboardTransport {
  private val mutableEvents = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 64)
  override val events = mutableEvents.asSharedFlow()
  private val mutableDiagnostics = MutableStateFlow(TransportDiagnostics(localPeerKey = localPeerKey, mode = "Preview test"))
  override val diagnostics = mutableDiagnostics.asStateFlow()
  val sentLive = mutableListOf<LivePreview>()

  override suspend fun start(profile: UserProfile) {
    mutableDiagnostics.value = mutableDiagnostics.value.copy(running = true)
  }

  override suspend fun sendReliable(operation: com.ditto.whiteboard.domain.BoardOperation) = Unit
  override fun sendLive(preview: LivePreview) { sentLive += preview }
  fun deliver(event: TransportEvent) { check(mutableEvents.tryEmit(event)) }
  override fun close() = Unit
}
