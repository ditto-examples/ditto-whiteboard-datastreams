package com.ditto.whiteboard.ui.troubleshooting

import com.ditto.whiteboard.transport.PresenceConnection
import kotlin.math.cos
import kotlin.math.sin

data class GraphPoint(val x: Float, val y: Float)

object PresenceGraphLayout {
  fun radial(
    root: String,
    nodes: Set<String>,
    connections: Set<PresenceConnection>,
    levelSpacing: Float = 150f,
  ): Map<String, GraphPoint> {
    if (nodes.isEmpty()) return emptyMap()
    val allNodes = nodes + root + connections.flatMap { listOf(it.peer1, it.peer2) }
    val adjacency = allNodes.associateWith { mutableSetOf<String>() }
    connections.forEach { edge ->
      adjacency.getValue(edge.peer1) += edge.peer2
      adjacency.getValue(edge.peer2) += edge.peer1
    }
    val distance = mutableMapOf(root to 0)
    val queue = ArrayDeque<String>().apply { add(root) }
    while (queue.isNotEmpty()) {
      val current = queue.removeFirst()
      adjacency.getValue(current).sorted().forEach { next ->
        if (next !in distance) {
          distance[next] = distance.getValue(current) + 1
          queue.add(next)
        }
      }
    }
    val disconnectedLevel = (distance.values.maxOrNull() ?: 0) + 1
    val levels = allNodes.filter { it != root }
      .groupBy { distance[it] ?: disconnectedLevel }
    return buildMap {
      put(root, GraphPoint(0f, 0f))
      levels.toSortedMap().forEach { (level, peers) ->
        val sorted = peers.sorted()
        val radius = levelSpacing * level
        sorted.forEachIndexed { index, peer ->
          val angle = -Math.PI / 2 + Math.PI * 2 * index / sorted.size
          put(peer, GraphPoint((cos(angle) * radius).toFloat(), (sin(angle) * radius).toFloat()))
        }
      }
    }
  }
}
