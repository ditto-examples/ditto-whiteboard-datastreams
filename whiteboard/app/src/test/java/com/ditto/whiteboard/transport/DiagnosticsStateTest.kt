package com.ditto.whiteboard.transport

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the transport's diagnostics bookkeeping seam, extracted precisely so the
 * ghost-peer and lost-update hazards can be verified in pure JVM without a live Ditto endpoint.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsStateTest {
  private fun newState() = DiagnosticsState(TransportDiagnostics(localPeerKey = "local"))

  @Test
  fun updatePeerCreatesThenMutatesEntry() {
    val state = newState()
    state.updatePeer("B") { it.copy(displayName = "Bob") }
    assertEquals("Bob", state.value.peers["B"]?.displayName)

    state.updatePeer("B") { it.copy(transmitMessagesPerSecond = 4.0) }
    // The follow-up mutation must preserve the earlier field, not reset it.
    assertEquals("Bob", state.value.peers["B"]?.displayName)
    assertEquals(4.0, state.value.peers["B"]?.transmitMessagesPerSecond ?: 0.0, 0.0)
  }

  @Test
  fun retainPeersDropsDepartedPeers() {
    val state = newState()
    state.updatePeer("A") { it.copy(displayName = "Amy") }
    state.updatePeer("B") { it.copy(displayName = "Bob") }

    state.retainPeers(setOf("A"))

    assertTrue(state.value.peers.containsKey("A"))
    assertFalse(state.value.peers.containsKey("B"))
  }

  @Test
  fun retainPeersOnEmptyVisibleSetClearsEverything() {
    val state = newState()
    state.updatePeer("A") { it.copy(displayName = "Amy") }
    state.retainPeers(emptySet())
    assertTrue(state.value.peers.isEmpty())
  }

  @Test
  fun concurrentUpdatesForDistinctPeersDoNotLoseEntries() = runTest {
    val state = newState()
    val peerCount = 200
    withContext(Dispatchers.Default) {
      (0 until peerCount).map { index ->
        async {
          val peer = "peer-$index"
          // Two mutations per peer so interleaved read-modify-writes have to compose atomically.
          state.updatePeer(peer) { it.copy(displayName = "name-$index") }
          state.updatePeer(peer) { it.copy(transmitMessagesPerSecond = index.toDouble()) }
        }
      }.awaitAll()
    }

    assertEquals(peerCount, state.value.peers.size)
    (0 until peerCount).forEach { index ->
      val peer = state.value.peers["peer-$index"]
      assertEquals("name-$index", peer?.displayName)
      assertEquals(index.toDouble(), peer?.transmitMessagesPerSecond ?: -1.0, 0.0)
    }
  }

  @Test
  fun ghostPeerIsNotResurrectedAfterRemoval() {
    // Reproduces the reported hazard shape: a peer that once had traffic is removed on presence
    // change (retainPeers), and a later diagnostics write for a still-visible peer must not bring
    // the departed peer back.
    val state = newState()
    state.updatePeer("A") { it.copy(displayName = "Amy") }
    state.updatePeer("ghost") { it.copy(transmitMessagesPerSecond = 9.0) }

    state.retainPeers(setOf("A"))
    assertNull(state.value.peers["ghost"])

    state.updatePeer("A") { it.copy(receiveMessagesPerSecond = 1.0) }
    assertNull(state.value.peers["ghost"])
    assertEquals(setOf("A"), state.value.peers.keys)
  }
}
