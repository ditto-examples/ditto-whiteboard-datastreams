package com.ditto.whiteboard.ui.troubleshooting

import com.ditto.whiteboard.transport.PresenceConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceGraphLayoutTest {
  @Test
  fun directViewExcludesPeersThatHaveNoDirectEdge() {
    val local = "local"
    val direct = PresenceConnection(local, "direct", "LAN")
    val multihop = PresenceConnection("direct", "multihop", "LAN")

    assertEquals(
      setOf(local, "direct"),
      presenceGraphNodes(
        localPeerKey = local,
        knownPeerKeys = setOf("direct", "multihop"),
        connections = setOf(direct),
        directOnly = true,
        hasObservedConnections = true,
      ),
    )
    assertEquals(
      setOf(local, "direct", "multihop"),
      presenceGraphNodes(
        localPeerKey = local,
        knownPeerKeys = setOf("direct", "multihop"),
        connections = setOf(direct, multihop),
        directOnly = false,
        hasObservedConnections = true,
      ),
    )
  }

  @Test
  fun directViewDoesNotFabricateEdgesWhenOnlyRemoteEdgesWereObserved() {
    assertEquals(
      setOf("local"),
      presenceGraphNodes(
        localPeerKey = "local",
        knownPeerKeys = setOf("a", "b"),
        connections = emptySet(),
        directOnly = true,
        hasObservedConnections = true,
      ),
    )
  }

  @Test
  fun bfsPlacesDirectPeersCloserThanSecondHopPeers() {
    val positions = PresenceGraphLayout.radial(
      root = "local",
      nodes = setOf("local", "a", "b"),
      connections = setOf(
        PresenceConnection("local", "a", "Lan"),
        PresenceConnection("a", "b", "BluetoothLE"),
      ),
    )
    assertEquals(GraphPoint(0f, 0f), positions["local"])
    assertTrue(distance(positions.getValue("a")) < distance(positions.getValue("b")))
  }

  @Test
  fun layoutIsStableRegardlessOfInputOrder() {
    val edges = listOf(
      PresenceConnection("root", "b", "Lan"),
      PresenceConnection("root", "a", "Lan"),
    )
    val first = PresenceGraphLayout.radial("root", linkedSetOf("root", "a", "b"), edges.toSet())
    val second = PresenceGraphLayout.radial("root", linkedSetOf("b", "root", "a"), edges.reversed().toSet())
    assertEquals(first, second)
  }

  @Test
  fun radialSpacingScalesWithDensityConvertedInput() {
    val edges = setOf(PresenceConnection("root", "peer", "Lan"))
    val baseline = PresenceGraphLayout.radial(
      "root",
      setOf("root", "peer"),
      edges,
      levelSpacing = 96f,
    )
    val highDensity = PresenceGraphLayout.radial(
      "root",
      setOf("root", "peer"),
      edges,
      levelSpacing = 288f,
    )

    assertEquals(
      distance(baseline.getValue("peer")) * 3f,
      distance(highDensity.getValue("peer")),
      0.01f,
    )
  }

  private fun distance(point: GraphPoint): Float = kotlin.math.hypot(point.x, point.y)
}
