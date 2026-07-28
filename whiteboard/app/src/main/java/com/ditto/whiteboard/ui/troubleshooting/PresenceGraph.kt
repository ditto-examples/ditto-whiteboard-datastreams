package com.ditto.whiteboard.ui.troubleshooting

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ditto.whiteboard.transport.PresenceConnection
import com.ditto.whiteboard.transport.TransportDiagnostics
import kotlin.math.hypot

private const val NODE_HIT_RADIUS = 44f
private const val TAP_MOVE_THRESHOLD = 12f
private const val LOCAL_NODE_RADIUS = 34f
private const val PEER_NODE_RADIUS = 29f
private const val NODE_LABEL_OFFSET = 22f
private const val NODE_LABEL_TEXT_SIZE = 28f
private const val MIN_GRAPH_ZOOM = 0.55f
private const val MAX_GRAPH_ZOOM = 3.5f

@Composable
fun PresenceGraph(
  diagnostics: TransportDiagnostics,
  directOnly: Boolean,
  selectedPeer: String?,
  onSelectPeer: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val local = diagnostics.localPeerKey
  val rawConnections = if (directOnly) {
    diagnostics.presenceConnections.filter { it.peer1 == local || it.peer2 == local }.toSet()
  } else diagnostics.presenceConnections
  val connections = if (rawConnections.isEmpty()) {
    diagnostics.peers.keys.map { PresenceConnection(local, it, diagnostics.peers[it]?.transports?.firstOrNull() ?: "Nearby") }.toSet()
  } else rawConnections
  val nodes = diagnostics.peers.keys + connections.flatMap { listOf(it.peer1, it.peer2) } + local
  val initial = remember(local, nodes, connections) { PresenceGraphLayout.radial(local, nodes, connections) }
  val nodePositions = remember { mutableStateMapOf<String, Offset>() }
  var canvasSize by remember { mutableStateOf(IntSize.Zero) }
  var zoom by remember { mutableFloatStateOf(1f) }
  var pan by remember { mutableStateOf(Offset.Zero) }

  LaunchedEffect(initial) {
    nodePositions.clear()
    initial.forEach { (peer, position) -> nodePositions[peer] = Offset(position.x, position.y) }
    zoom = 1f
    pan = Offset.Zero
  }

  fun screenPosition(peer: String): Offset {
    val logical = nodePositions[peer] ?: Offset.Zero
    return Offset(canvasSize.width / 2f, canvasSize.height / 2f) + pan + logical * zoom
  }

  Box(
    modifier
      .background(MaterialTheme.colorScheme.surfaceVariant)
      .semantics {
        contentDescription = "Presence graph with ${diagnostics.peers.size} nearby peers. Drag to pan, pinch to zoom, or drag a peer."
      }
      .onSizeChanged { canvasSize = it }
      // Key only on identity: zoom/pan are mutated *inside* this gesture, so keying on them would
      // cancel and relaunch the block mid-drag every frame. The closure reads their live values.
      .pointerInput(nodes, connections) {
        awaitEachGesture {
          val down = awaitFirstDown(requireUnconsumed = false)
          val hit = nodes.minByOrNull { peer -> (screenPosition(peer) - down.position).getDistance() }
            ?.takeIf { (screenPosition(it) - down.position).getDistance() <= NODE_HIT_RADIUS }
          var moved = 0f
          while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.count { it.pressed }
            if (pressed >= 2) {
              zoom = (zoom * event.calculateZoom()).coerceIn(MIN_GRAPH_ZOOM, MAX_GRAPH_ZOOM)
              pan += event.calculatePan()
              event.changes.forEach { it.consume() }
            } else {
              event.changes.firstOrNull { it.positionChanged() }?.let { change ->
                val delta = change.position - change.previousPosition
                moved += delta.getDistance()
                if (hit != null && hit != local) {
                  nodePositions[hit] = (nodePositions[hit] ?: Offset.Zero) + delta / zoom
                } else {
                  pan += delta
                }
                change.consume()
              }
            }
            if (event.changes.all { !it.pressed }) {
              if (moved < TAP_MOVE_THRESHOLD && hit != null && hit != local) onSelectPeer(hit)
              break
            }
          }
        }
      },
  ) {
  val primary = MaterialTheme.colorScheme.primary
  val secondary = MaterialTheme.colorScheme.secondary
  val outline = MaterialTheme.colorScheme.outline
  val onSurface = MaterialTheme.colorScheme.onSurface
    val edgeColors = connections.associateWith { transportColor(it.transport) }
    Canvas(Modifier.fillMaxSize()) {
      connections.groupBy { setOf(it.peer1, it.peer2) }.forEach { (_, parallel) ->
        parallel.forEachIndexed { index, edge ->
          val start = screenPosition(edge.peer1)
          val end = screenPosition(edge.peer2)
          val dx = end.x - start.x
          val dy = end.y - start.y
          val length = hypot(dx, dy).coerceAtLeast(1f)
          val offsetAmount = (index - (parallel.size - 1) / 2f) * 8f
          val perpendicular = Offset(-dy / length * offsetAmount, dx / length * offsetAmount)
          drawLine(
            edgeColors.getValue(edge),
            start + perpendicular,
            end + perpendicular,
            strokeWidth = 4f,
            cap = StrokeCap.Round,
          )
        }
      }
      nodes.forEach { peer ->
        val position = screenPosition(peer)
        val unknown = peer != local && peer !in diagnostics.peers
        val radius = if (peer == local) LOCAL_NODE_RADIUS else PEER_NODE_RADIUS
        drawCircle(
          color = when {
            peer == local -> primary
            unknown -> outline
            else -> secondary
          },
          radius = radius,
          center = position,
        )
        if (peer == selectedPeer) drawCircle(primary, radius + 7f, position, style = Stroke(4f))
        val label = when {
          peer == local -> "You"
          unknown -> "Cloud"
          else -> diagnostics.peers[peer]?.displayName ?: peer.takeLast(6)
        }
        drawContext.canvas.nativeCanvas.drawText(
          label,
          position.x,
          position.y + radius + NODE_LABEL_OFFSET,
          Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = onSurface.toArgb()
            textAlign = Paint.Align.CENTER
            textSize = NODE_LABEL_TEXT_SIZE
          },
        )
      }
    }
    FilledTonalIconButton(
      onClick = {
        nodePositions.clear()
        initial.forEach { (peer, position) -> nodePositions[peer] = Offset(position.x, position.y) }
        zoom = 1f
        pan = Offset.Zero
      },
      modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
    ) { Icon(Icons.Default.RestartAlt, contentDescription = "Reset graph") }
  }
}

@Composable
fun transportColor(transport: String): Color = when {
  transport.contains("Bluetooth", ignoreCase = true) -> Color(0xFF246BCE)
  transport.contains("Aware", ignoreCase = true) -> Color(0xFF00897B)
  transport.contains("Lan", ignoreCase = true) || transport.contains("Tcp", ignoreCase = true) -> Color(0xFFF57C00)
  transport.contains("Web", ignoreCase = true) || transport.contains("Server", ignoreCase = true) -> Color(0xFF7B1FA2)
  else -> MaterialTheme.colorScheme.outline
}
