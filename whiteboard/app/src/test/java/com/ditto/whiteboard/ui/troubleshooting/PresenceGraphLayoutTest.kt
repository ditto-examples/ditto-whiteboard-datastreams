package com.ditto.whiteboard.ui.troubleshooting

import com.ditto.whiteboard.transport.PresenceConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceGraphLayoutTest {
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

  private fun distance(point: GraphPoint): Float = kotlin.math.hypot(point.x, point.y)
}
