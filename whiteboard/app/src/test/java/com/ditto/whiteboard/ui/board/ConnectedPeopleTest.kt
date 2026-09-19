package com.ditto.whiteboard.ui.board

import com.ditto.whiteboard.domain.BoardState
import com.ditto.whiteboard.domain.UserProfile
import com.ditto.whiteboard.transport.PeerDiagnostics
import com.ditto.whiteboard.transport.TransportDiagnostics
import com.ditto.whiteboard.ui.BoardUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlinx.collections.immutable.persistentMapOf

class ConnectedPeopleTest {
  @Test
  fun rosterContainsLocalAndCurrentlyPresentCompatiblePeersOnly() {
    val state = BoardUiState(
      board = BoardState(
        profiles = persistentMapOf(
          "local" to UserProfile("local", "Ada", 0xFF0057B8.toInt()),
          "connected" to UserProfile("connected", "Grace", 0xFF007A3D.toInt()),
          "stale" to UserProfile("stale", "Linus", 0xFFC62828.toInt()),
        ),
      ),
      diagnostics = TransportDiagnostics(
        localPeerKey = "local",
        running = true,
        peers = mapOf(
          "connected" to PeerDiagnostics(peerKey = "connected", transports = setOf("LAN")),
          "incompatible" to PeerDiagnostics(peerKey = "incompatible", displayName = "Old app"),
        ),
        incompatiblePeers = mapOf("incompatible" to 1),
      ),
    )

    val people = state.connectedPeople()

    assertEquals(listOf("Ada", "Grace"), people.map(ConnectedPerson::displayName))
    assertEquals(0xFF007A3D.toInt(), people.last().colorArgb)
    assertFalse(people.any { it.peerKey == "stale" || it.peerKey == "incompatible" })
  }

  @Test
  fun rosterUsesPresenceColorWhileProfileIsStillSyncing() {
    val state = BoardUiState(
      diagnostics = TransportDiagnostics(
        localPeerKey = "local",
        peers = mapOf(
          "remote" to PeerDiagnostics(
            peerKey = "remote",
            displayName = "Nearby artist",
            colorArgb = 0xFF7B1FA2.toInt(),
          ),
        ),
      ),
    )

    assertEquals(0xFF7B1FA2.toInt(), state.connectedPeople().last().colorArgb)
  }
}
