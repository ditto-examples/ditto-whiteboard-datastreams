package com.ditto.whiteboard.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the contract `DittoWhiteboardTransport.dispatchSnapshotControl` relies on.
 *
 * Snapshot rejections replay buffered operations through the session's prepare/commit/apply
 * handshake (two 30-second awaits each) and enqueue an acknowledgement onto a bounded outbox. They
 * used to run inline on the single reliable-ingress worker that decodes for *every* peer, so one
 * stalled peer backed up `reliableInbound` for all of them and cascaded into mesh-wide stream
 * teardowns. They now run on bounded per-peer lanes with these guarantees.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SnapshotControlDispatchTest {
  private fun dispatcher(
    scope: kotlinx.coroutines.CoroutineScope,
    capacityPerPeer: Int,
    completed: MutableList<String>,
  ) = PerPeerOperationDispatcher<suspend () -> Unit>(
    scope = scope,
    capacityPerPeer = capacityPerPeer,
  ) { peer, work ->
    work()
    completed += peer
  }

  @Test
  fun aStalledRejectionNeverDelaysAnotherPeersSnapshotControlWork() = runTest {
    val completed = mutableListOf<String>()
    val stalledReplay = CompletableDeferred<Unit>()
    val control = dispatcher(backgroundScope, capacityPerPeer = 8, completed = completed)

    // "stalled" models a peer whose buffered-operation replay is awaiting a board acknowledgement.
    assertTrue(control.tryDispatch("stalled") { stalledReplay.await() })
    assertTrue(control.tryDispatch("healthy") { })
    runCurrent()

    assertEquals(
      "the healthy peer's acknowledgement must not wait behind the stalled peer",
      listOf("healthy"),
      completed,
    )

    stalledReplay.complete(Unit)
    runCurrent()
    assertEquals(listOf("healthy", "stalled"), completed)
  }

  @Test
  fun workForOnePeerStaysOrdered() = runTest {
    val order = mutableListOf<String>()
    val gate = CompletableDeferred<Unit>()
    val control = PerPeerOperationDispatcher<suspend () -> Unit>(
      scope = backgroundScope,
      capacityPerPeer = 8,
    ) { _, work -> work() }

    assertTrue(control.tryDispatch("peer") { gate.await(); order += "reject-superseded" })
    assertTrue(control.tryDispatch("peer") { order += "ack" })
    runCurrent()
    assertTrue("the second item must not overtake the blocked first", order.isEmpty())

    gate.complete(Unit)
    runCurrent()
    assertEquals(listOf("reject-superseded", "ack"), order)
  }

  /**
   * Saturation must be reported, not absorbed: the transport turns a false return into a
   * `recoverStream`, so the peer reconnects and reconciles from a fresh Hello rather than the
   * caller blocking on a full lane.
   */
  @Test
  fun saturationIsReportedSoTheCallerCanRecoverTheStream() = runTest {
    val blocked = CompletableDeferred<Unit>()
    val control = PerPeerOperationDispatcher<suspend () -> Unit>(
      scope = backgroundScope,
      capacityPerPeer = 2,
    ) { _, work -> work() }

    assertTrue(control.tryDispatch("peer") { blocked.await() })
    runCurrent()
    // One item is in flight; the lane's buffer holds `capacityPerPeer` more.
    assertTrue(control.tryDispatch("peer") { })
    assertTrue(control.tryDispatch("peer") { })

    assertFalse("a saturated lane must refuse rather than suspend", control.tryDispatch("peer") { })

    blocked.complete(Unit)
    runCurrent()
    assertTrue("the lane drains once unblocked", control.tryDispatch("peer") { })
  }
}
