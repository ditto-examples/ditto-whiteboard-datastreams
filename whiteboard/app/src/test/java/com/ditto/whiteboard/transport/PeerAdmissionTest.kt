package com.ditto.whiteboard.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerAdmissionTest {
  @Test
  fun densePresenceGraphCannotReintroduceIgnoredPeersThroughEdges() {
    val discovered = (1..100).map { "peer-${it.toString().padStart(3, '0')}" }
    val admitted = admittedPeerKeys("peer-000", discovered)
    val present = admitted - "peer-000"
    val denseConnections = discovered.flatMap { first ->
      discovered.map { second -> PresenceConnection(first, second, "LAN") }
    }

    val filtered = admittedPresenceConnections("peer-000", present, denseConnections)

    assertEquals(MAX_CONNECTED_PEERS, admitted.size)
    assertTrue(filtered.all { it.peer1 in admitted && it.peer2 in admitted })
    assertFalse(filtered.any { it.peer1 == "peer-100" || it.peer2 == "peer-100" })
  }

  @Test
  fun localPeerOutsideDeterministicCapacityAdmitsNoAsymmetricSubset() {
    val discovered = (1..MAX_CONNECTED_PEERS).map { "a-$it" }
    assertTrue(admittedPeerKeys("z-local", discovered).isEmpty())
  }
}
