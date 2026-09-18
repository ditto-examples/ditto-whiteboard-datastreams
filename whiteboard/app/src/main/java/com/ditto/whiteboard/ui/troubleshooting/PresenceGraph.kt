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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateMap
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ditto.whiteboard.transport.PresenceConnection
import com.ditto.whiteboard.transport.TransportDiagnostics
import com.ditto.whiteboard.R
import kotlin.math.hypot
import kotlin.math.roundToInt

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
  val hasObservedConnections = diagnostics.presenceConnections.isNotEmpty()
  val connections = if (rawConnections.isEmpty() && !hasObservedConnections) {
    diagnostics.peers.keys.map { PresenceConnection(local, it, diagnostics.peers[it]?.transports?.firstOrNull() ?: "Nearby") }.toSet()
  } else rawConnections
  val nodes = presenceGraphNodes(
    localPeerKey = local,
    knownPeerKeys = diagnostics.peers.keys,
    connections = connections,
    directOnly = directOnly,
    hasObservedConnections = hasObservedConnections,
  )
  val density = LocalDensity.current
  val nodeHitRadiusPx = with(density) { 24.dp.toPx() }
  val tapMoveThresholdPx = with(density) { 12.dp.toPx() }
  val localNodeRadiusPx = with(density) { 20.dp.toPx() }
  val peerNodeRadiusPx = with(density) { 17.dp.toPx() }
  val nodeLabelGapPx = with(density) { 4.dp.toPx() }
  val nodeLabelTextSizePx = with(density) { 14.sp.toPx() }
  val nodeLabelPaddingPx = with(density) { 2.dp.toPx() }
  val nodeLabelCornerRadiusPx = with(density) { 3.dp.toPx() }
  val graphLevelSpacingPx = with(density) { 96.dp.toPx() }
  val parallelEdgeOffsetPx = with(density) { 4.dp.toPx() }
  val edgeStrokeWidthPx = with(density) { 2.dp.toPx() }
  val selectedRingOffsetPx = with(density) { 4.dp.toPx() }
  val selectedRingStrokePx = with(density) { 2.dp.toPx() }
  val initial = remember(local, nodes, connections, graphLevelSpacingPx) {
    PresenceGraphLayout.radial(local, nodes, connections, graphLevelSpacingPx)
  }
  val nodePositions = remember(initial) {
    initial
      .map { (peer, position) -> peer to Offset(position.x, position.y) }
      .toMutableStateMap()
  }
  var canvasSize by remember { mutableStateOf(IntSize.Zero) }
  var zoom by remember(initial) { mutableFloatStateOf(1f) }
  var pan by remember(initial) { mutableStateOf(Offset.Zero) }
  val localLabel = stringResource(R.string.you)
  val cloudLabel = stringResource(R.string.transport_cloud)
  val graphDescription = pluralStringResource(
    R.plurals.presence_graph_description,
    diagnostics.peers.size,
    diagnostics.peers.size,
  )
  val inspectPeerAction = stringResource(R.string.action_select_peer)
  val peerLabels = nodes.associateWith { peer ->
    when {
      peer == local -> localLabel
      peer !in diagnostics.peers -> cloudLabel
      else -> diagnostics.peers[peer]?.displayName ?: peer.takeLast(6)
    }
  }
  val peerDescriptions = nodes
    .filterNot { it == local }
    .associateWith { peer ->
      stringResource(
        if (peer == selectedPeer) {
          R.string.presence_peer_selected_description
        } else {
          R.string.presence_peer_description
        },
        peerLabels.getValue(peer),
      )
    }
  fun screenPosition(peer: String): Offset {
    val logical = nodePositions[peer] ?: Offset.Zero
    return Offset(canvasSize.width / 2f, canvasSize.height / 2f) + pan + logical * zoom
  }

  Box(
    modifier
      .background(MaterialTheme.colorScheme.surfaceVariant)
      .semantics {
        contentDescription = graphDescription
      }
      .onSizeChanged { canvasSize = it }
      // Key only on identity: zoom/pan are mutated *inside* this gesture, so keying on them would
      // cancel and relaunch the block mid-drag every frame. The closure reads their live values.
      .pointerInput(nodes, connections, nodeHitRadiusPx, tapMoveThresholdPx) {
        awaitEachGesture {
          val down = awaitFirstDown(requireUnconsumed = false)
          val hit = nodes.minByOrNull { peer -> (screenPosition(peer) - down.position).getDistance() }
            ?.takeIf { (screenPosition(it) - down.position).getDistance() <= nodeHitRadiusPx }
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
              if (moved < tapMoveThresholdPx && hit != null && hit != local) onSelectPeer(hit)
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
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val parallelConnections = remember(connections) {
      connections
        .groupBy { edge ->
          if (edge.peer1 <= edge.peer2) edge.peer1 to edge.peer2 else edge.peer2 to edge.peer1
        }
        .values
        .toList()
    }
    val edgeColors = remember(connections, outline) {
      connections.associateWith { transportColor(it.transport, outline) }
    }
    val labelPaint = remember(onSurface, nodeLabelTextSizePx) {
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = onSurface.toArgb()
        textAlign = Paint.Align.CENTER
        textSize = nodeLabelTextSizePx
      }
    }
    val labelBackgroundPaint = remember(surfaceVariant) {
      Paint(Paint.ANTI_ALIAS_FLAG).apply { color = surfaceVariant.toArgb() }
    }
    Canvas(Modifier.fillMaxSize()) {
      parallelConnections.forEach { parallel ->
        parallel.forEachIndexed { index, edge ->
          val start = screenPosition(edge.peer1)
          val end = screenPosition(edge.peer2)
          val dx = end.x - start.x
          val dy = end.y - start.y
          val length = hypot(dx, dy).coerceAtLeast(1f)
          val offsetAmount =
            (index - (parallel.size - 1) / 2f) * parallelEdgeOffsetPx
          val perpendicular = Offset(-dy / length * offsetAmount, dx / length * offsetAmount)
          drawLine(
            edgeColors.getValue(edge),
            start + perpendicular,
            end + perpendicular,
            strokeWidth = edgeStrokeWidthPx,
            cap = StrokeCap.Round,
          )
        }
      }
      nodes.forEach { peer ->
        val position = screenPosition(peer)
        val unknown = peer != local && peer !in diagnostics.peers
        val radius = if (peer == local) localNodeRadiusPx else peerNodeRadiusPx
        drawCircle(
          color = when {
            peer == local -> primary
            unknown -> outline
            else -> secondary
          },
          radius = radius,
          center = position,
        )
        if (peer == selectedPeer) {
          drawCircle(
            primary,
            radius + selectedRingOffsetPx,
            position,
            style = Stroke(selectedRingStrokePx),
          )
        }
        val label = peerLabels.getValue(peer)
        val halfLabelWidth = labelPaint.measureText(label) / 2f
        val fontMetrics = labelPaint.fontMetrics
        val labelBaseline = position.y + radius + nodeLabelGapPx - fontMetrics.ascent
        drawContext.canvas.nativeCanvas.drawRoundRect(
          position.x - halfLabelWidth - nodeLabelPaddingPx,
          labelBaseline + fontMetrics.ascent - nodeLabelPaddingPx,
          position.x + halfLabelWidth + nodeLabelPaddingPx,
          labelBaseline + fontMetrics.descent + nodeLabelPaddingPx,
          nodeLabelCornerRadiusPx,
          nodeLabelCornerRadiusPx,
          labelBackgroundPaint,
        )
        drawContext.canvas.nativeCanvas.drawText(
          label,
          position.x,
          labelBaseline,
          labelPaint,
        )
      }
    }
    nodes.filterNot { it == local }.forEach { peer ->
      val position = screenPosition(peer)
      Box(
        Modifier
          .offset {
            IntOffset(
              (position.x - nodeHitRadiusPx).roundToInt(),
              (position.y - nodeHitRadiusPx).roundToInt(),
            )
          }
          .size(48.dp)
          .semantics {
            contentDescription = peerDescriptions.getValue(peer)
            role = Role.Button
            selected = peer == selectedPeer
            onClick(label = inspectPeerAction) {
              onSelectPeer(peer)
              true
            }
          },
      )
    }
    FilledTonalIconButton(
      onClick = {
        nodePositions.clear()
        initial.forEach { (peer, position) -> nodePositions[peer] = Offset(position.x, position.y) }
        zoom = 1f
        pan = Offset.Zero
      },
      modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
    ) { Icon(Icons.Default.RestartAlt, contentDescription = stringResource(R.string.action_reset_graph)) }
  }
}

internal fun presenceGraphNodes(
  localPeerKey: String,
  knownPeerKeys: Set<String>,
  connections: Set<PresenceConnection>,
  directOnly: Boolean,
  hasObservedConnections: Boolean,
): Set<String> {
  val connectedNodes = connections.flatMapTo(mutableSetOf()) { listOf(it.peer1, it.peer2) }
  return if (directOnly && hasObservedConnections) {
    connectedNodes + localPeerKey
  } else {
    knownPeerKeys + connectedNodes + localPeerKey
  }
}

@Composable
fun transportColor(transport: String): Color =
  transportColor(transport, MaterialTheme.colorScheme.outline)

private fun transportColor(transport: String, fallback: Color): Color = when {
  transport.contains("Bluetooth", ignoreCase = true) -> Color(0xFF246BCE)
  transport.contains("Aware", ignoreCase = true) -> Color(0xFF00897B)
  transport.contains("Lan", ignoreCase = true) || transport.contains("Tcp", ignoreCase = true) -> Color(0xFFF57C00)
  transport.contains("Web", ignoreCase = true) || transport.contains("Server", ignoreCase = true) -> Color(0xFF7B1FA2)
  else -> fallback
}
