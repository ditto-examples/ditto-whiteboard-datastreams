package com.ditto.whiteboard.ui.board

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ditto.whiteboard.domain.BOARD_HEIGHT
import com.ditto.whiteboard.domain.BOARD_WIDTH
import com.ditto.whiteboard.domain.DEFAULT_ERASER_RADIUS
import com.ditto.whiteboard.domain.DEFAULT_STROKE_WIDTH
import com.ditto.whiteboard.domain.BoardObject
import com.ditto.whiteboard.domain.BoardState
import com.ditto.whiteboard.domain.DrawingTool
import com.ditto.whiteboard.domain.LivePreview
import com.ditto.whiteboard.domain.LogicalPoint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val ZOOM_STEP = 1.25f

/** Board-unit spacing between grid dots, and between the emphasized "major" dots. */
private const val GRID_MINOR_STEP = 120
private const val GRID_MAJOR_STEP = 480

/** Alpha applied to remote vs. the local in-progress preview so live strokes read as tentative. */
private const val REMOTE_PREVIEW_ALPHA = 0.55f
private const val LOCAL_PREVIEW_ALPHA = 0.72f

@Composable
fun BoardCanvas(
  boardState: BoardState,
  previews: List<LivePreview>,
  tool: DrawingTool,
  colorArgb: Int,
  onPreview: (List<LogicalPoint>) -> Unit,
  onCommit: (List<LogicalPoint>) -> Unit,
  connectivityMessage: String? = null,
  modifier: Modifier = Modifier,
) {
  var viewport by remember { mutableStateOf(BoardViewport()) }
  var activePoints by remember { mutableStateOf(emptyList<LogicalPoint>()) }
  var viewportInitialized by remember { mutableStateOf(false) }

  LaunchedEffect(viewport.canvasWidth, viewport.canvasHeight) {
    if (viewport.canvasWidth == 0 || viewport.canvasHeight == 0) return@LaunchedEffect
    viewport = if (!viewportInitialized) {
      viewportInitialized = true
      viewport.copy(zoom = viewport.fillZoom(), panX = 0f, panY = 0f).withConstrainedPan()
    } else {
      viewport.withConstrainedPan()
    }
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Color(0xFF252A2D))
  ) {
    Canvas(
      Modifier
        .fillMaxSize()
        .onSizeChanged { viewport = viewport.copy(canvasWidth = it.width, canvasHeight = it.height) }
        .semantics {
          contentDescription =
            "Shared 3840 by 2160 drawing board. Draw with one finger or a stylus. Pan and zoom with two fingers."
        }
        .pointerInput(tool, colorArgb) {
        awaitEachGesture {
          val down = awaitFirstDown(requireUnconsumed = false)
          var drawing = true
          activePoints = listOf(viewport.logicalPoint(down.position.x, down.position.y))
          onPreview(activePoints)
          while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.count { it.pressed }
            if (pressed >= 2) {
              // A second finger switches from drawing to pan/zoom: the in-progress stroke is
              // abandoned (never committed). Any preview already broadcast to peers self-expires.
              drawing = false
              activePoints = emptyList()
              val centroid = event.calculateCentroid()
              val panDelta = event.calculatePan()
              viewport = viewport
                .withZoom(viewport.zoom * event.calculateZoom(), centroid.x, centroid.y)
                .panBy(panDelta.x, panDelta.y)
              event.changes.forEach { it.consume() }
            } else if (drawing) {
              event.changes.firstOrNull { it.positionChanged() }?.let { change ->
                val point = viewport.logicalPoint(change.position.x, change.position.y)
                activePoints = when (tool) {
                  DrawingTool.Pen, DrawingTool.Eraser -> activePoints + point
                  else -> listOf(activePoints.firstOrNull() ?: point, point)
                }
                onPreview(activePoints)
                change.consume()
              }
            }
            if (event.changes.all { !it.pressed }) {
              if (drawing && activePoints.isNotEmpty()) onCommit(activePoints)
              activePoints = emptyList()
              break
            }
          }
        }
      },
    ) {
      val scale = viewport.fitScale() * viewport.zoom
      val boardWidth = BOARD_WIDTH * scale
      val boardHeight = BOARD_HEIGHT * scale
      val origin = Offset(viewport.originX(), viewport.originY())
      val visibleLeft = max(0f, origin.x)
      val visibleTop = max(0f, origin.y)
      val visibleRight = min(size.width, origin.x + boardWidth)
      val visibleBottom = min(size.height, origin.y + boardHeight)
      if (visibleRight > visibleLeft && visibleBottom > visibleTop) {
        drawRect(
          Color(0xFFFFFEFC),
          topLeft = Offset(visibleLeft, visibleTop),
          size = Size(visibleRight - visibleLeft, visibleBottom - visibleTop),
        )
      }
      clipRect(visibleLeft, visibleTop, visibleRight, visibleBottom) {
        withTransform({
          translate(origin.x, origin.y)
          scale(scale, scale, Offset.Zero)
        }) {
          drawBoardGrid(scale)
          boardState.objects.values.sortedBy(BoardObject::stamp).forEach(::drawBoardObject)
          previews.forEach { preview ->
            drawPreview(preview.tool, preview.points, Color(preview.colorArgb).copy(alpha = REMOTE_PREVIEW_ALPHA))
          }
          if (activePoints.isNotEmpty()) drawPreview(tool, activePoints, Color(colorArgb).copy(alpha = LOCAL_PREVIEW_ALPHA))
        }
      }
      drawRect(Color(0xFF899197), topLeft = origin, size = Size(boardWidth, boardHeight), style = Stroke(1.5f))
    }

    Surface(
      modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(12.dp),
      shape = MaterialTheme.shapes.extraLarge,
      color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
      tonalElevation = 3.dp,
      shadowElevation = 3.dp,
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { viewport = viewport.withZoom(viewport.zoom / ZOOM_STEP) }) {
          Icon(Icons.Default.ZoomOut, contentDescription = "Zoom out")
        }
        Text(
          text = "${(viewport.zoom * 100).roundToInt()}%",
          style = MaterialTheme.typography.labelLarge,
        )
        IconButton(onClick = { viewport = viewport.withZoom(viewport.zoom * ZOOM_STEP) }) {
          Icon(Icons.Default.ZoomIn, contentDescription = "Zoom in")
        }
        TextButton(
          onClick = { viewport = viewport.withZoom(1f) },
          contentPadding = PaddingValues(horizontal = 8.dp),
        ) { Text("Fit") }
        TextButton(
          onClick = { viewport = viewport.withZoom(viewport.fillZoom()) },
          contentPadding = PaddingValues(horizontal = 8.dp),
        ) { Text("Fill") }
      }
    }

    connectivityMessage?.let { message ->
      Surface(
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .safeDrawingPadding()
          .padding(12.dp)
          .fillMaxWidth(0.94f),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.96f),
        tonalElevation = 2.dp,
      ) {
        Text(
          text = message,
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
          style = MaterialTheme.typography.bodySmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

private fun DrawScope.drawBoardGrid(scale: Float) {
  if (scale <= 0f) return
  val minorColor = Color(0xFFDCE3E8)
  val majorColor = Color(0xFFBCC8D0)
  for (y in 0..BOARD_HEIGHT step GRID_MINOR_STEP) {
    for (x in 0..BOARD_WIDTH step GRID_MINOR_STEP) {
      val major = x % GRID_MAJOR_STEP == 0 && y % GRID_MAJOR_STEP == 0
      drawCircle(
        color = if (major) majorColor else minorColor,
        radius = (if (major) 1.8f else 1.1f) / scale,
        center = Offset(x.toFloat(), y.toFloat()),
      )
    }
  }
}

private fun DrawScope.drawBoardObject(boardObject: BoardObject) {
  val color = Color(boardObject.colorArgb)
  when (boardObject) {
    is BoardObject.Freehand -> drawLogicalPath(boardObject.points, color, boardObject.width.toFloat())
    is BoardObject.Line -> drawLine(color, boardObject.start.offset, boardObject.end.offset, boardObject.width.toFloat(), StrokeCap.Round)
    is BoardObject.Rectangle -> {
      val left = minOf(boardObject.start.x, boardObject.end.x).toFloat()
      val top = minOf(boardObject.start.y, boardObject.end.y).toFloat()
      drawRect(
        color,
        Offset(left, top),
        Size(abs(boardObject.end.x - boardObject.start.x).toFloat(), abs(boardObject.end.y - boardObject.start.y).toFloat()),
        style = Stroke(boardObject.width.toFloat()),
      )
    }
    is BoardObject.Ellipse -> {
      val left = minOf(boardObject.start.x, boardObject.end.x).toFloat()
      val top = minOf(boardObject.start.y, boardObject.end.y).toFloat()
      drawOval(
        color,
        Offset(left, top),
        Size(abs(boardObject.end.x - boardObject.start.x).toFloat(), abs(boardObject.end.y - boardObject.start.y).toFloat()),
        style = Stroke(boardObject.width.toFloat()),
      )
    }
    is BoardObject.Text -> drawContext.canvas.nativeCanvas.drawText(
      boardObject.text,
      boardObject.anchor.x.toFloat(),
      boardObject.anchor.y.toFloat(),
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = boardObject.colorArgb
        textSize = boardObject.size.toFloat()
      },
    )
  }
}

private fun DrawScope.drawPreview(tool: DrawingTool, points: List<LogicalPoint>, color: Color) {
  if (points.isEmpty()) return
  when (tool) {
    DrawingTool.Pen -> drawLogicalPath(points, color, DEFAULT_STROKE_WIDTH.toFloat())
    DrawingTool.Eraser -> drawLogicalPath(points, Color(0xFFE05252).copy(alpha = color.alpha), (DEFAULT_ERASER_RADIUS * 2).toFloat())
    DrawingTool.Line -> if (points.size > 1) drawLine(color, points.first().offset, points.last().offset, DEFAULT_STROKE_WIDTH.toFloat(), StrokeCap.Round)
    DrawingTool.Rectangle -> if (points.size > 1) {
      val first = points.first(); val last = points.last()
      drawRect(color, Offset(minOf(first.x, last.x).toFloat(), minOf(first.y, last.y).toFloat()), Size(abs(last.x - first.x).toFloat(), abs(last.y - first.y).toFloat()), style = Stroke(DEFAULT_STROKE_WIDTH.toFloat()))
    }
    DrawingTool.Ellipse -> if (points.size > 1) {
      val first = points.first(); val last = points.last()
      drawOval(color, Offset(minOf(first.x, last.x).toFloat(), minOf(first.y, last.y).toFloat()), Size(abs(last.x - first.x).toFloat(), abs(last.y - first.y).toFloat()), style = Stroke(DEFAULT_STROKE_WIDTH.toFloat()))
    }
    DrawingTool.Text -> drawCircle(color, 12f, points.first().offset)
  }
}

private fun DrawScope.drawLogicalPath(points: List<LogicalPoint>, color: Color, width: Float) {
  if (points.size == 1) {
    drawCircle(color, width / 2f, points.first().offset)
    return
  }
  if (points.size < 2) return
  val path = Path().apply {
    moveTo(points.first().x.toFloat(), points.first().y.toFloat())
    points.drop(1).forEach { lineTo(it.x.toFloat(), it.y.toFloat()) }
  }
  drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round))
}

private val LogicalPoint.offset: Offset get() = Offset(x.toFloat(), y.toFloat())
