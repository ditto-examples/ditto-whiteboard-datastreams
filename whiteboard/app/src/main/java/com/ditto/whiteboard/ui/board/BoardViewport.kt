package com.ditto.whiteboard.ui.board

import com.ditto.whiteboard.domain.BOARD_HEIGHT
import com.ditto.whiteboard.domain.BOARD_WIDTH
import com.ditto.whiteboard.domain.LogicalPoint
import kotlin.math.max
import kotlin.math.min

/** Smallest allowed zoom; below the fit scale the board would float inside empty gutters. */
const val MIN_VIEWPORT_ZOOM = 0.75f

/** Largest allowed zoom, enough to work on fine detail without losing all context. */
const val MAX_VIEWPORT_ZOOM = 12f

/**
 * Pure, immutable description of how the fixed [BOARD_WIDTH]×[BOARD_HEIGHT] board is mapped onto the
 * on-screen canvas — the fit scale, the current [zoom], and the [panX]/[panY] offset.
 *
 * All camera math lives here (screen↔board conversion, zoom-about-a-focal-point, pan clamping) so it
 * can be unit-tested without Compose. [BoardCanvas] holds one of these in a `remember`ed state and
 * replaces it wholesale on each gesture; nothing here touches the Compose runtime.
 */
data class BoardViewport(
  val canvasWidth: Int = 0,
  val canvasHeight: Int = 0,
  val zoom: Float = 1f,
  val panX: Float = 0f,
  val panY: Float = 0f,
  val boardWidth: Int = BOARD_WIDTH,
  val boardHeight: Int = BOARD_HEIGHT,
) {
  /** Scale at which the whole board just fits inside the canvas (zoom == 1). */
  fun fitScale(): Float =
    min(canvasWidth / boardWidth.toFloat(), canvasHeight / boardHeight.toFloat())

  /** Zoom (relative to fit) at which the board covers the canvas with no letterboxing. */
  fun fillZoom(): Float {
    val fit = fitScale()
    if (fit <= 0f) return 1f
    val cover = max(canvasWidth / boardWidth.toFloat(), canvasHeight / boardHeight.toFloat())
    return (cover / fit).coerceIn(MIN_VIEWPORT_ZOOM, MAX_VIEWPORT_ZOOM)
  }

  /** Top-left of the board in canvas pixels, given a zoom and pan. */
  fun originX(atZoom: Float = zoom, atPanX: Float = panX): Float =
    (canvasWidth - boardWidth * fitScale() * atZoom) / 2f + atPanX

  fun originY(atZoom: Float = zoom, atPanY: Float = panY): Float =
    (canvasHeight - boardHeight * fitScale() * atZoom) / 2f + atPanY

  private fun clampPan(candidateX: Float, candidateY: Float, atZoom: Float): Pair<Float, Float> {
    val scale = fitScale() * atZoom
    if (scale <= 0f) return 0f to 0f
    val horizontalTravel = max(0f, (boardWidth * scale - canvasWidth) / 2f)
    val verticalTravel = max(0f, (boardHeight * scale - canvasHeight) / 2f)
    return candidateX.coerceIn(-horizontalTravel, horizontalTravel) to
      candidateY.coerceIn(-verticalTravel, verticalTravel)
  }

  /** Re-clamps the current pan to the current zoom (used after the canvas is resized). */
  fun withConstrainedPan(): BoardViewport {
    val (px, py) = clampPan(panX, panY, zoom)
    return copy(panX = px, panY = py)
  }

  /** Pans by a screen-pixel delta, keeping the board within travel bounds. */
  fun panBy(deltaX: Float, deltaY: Float): BoardViewport {
    val (px, py) = clampPan(panX + deltaX, panY + deltaY, zoom)
    return copy(panX = px, panY = py)
  }

  /**
   * Zooms to [targetZoom] (clamped) while keeping the board point under [focalX]/[focalY] fixed on
   * screen — the standard pinch-to-zoom-about-the-fingers behaviour. Defaults to the canvas centre.
   */
  fun withZoom(
    targetZoom: Float,
    focalX: Float = canvasWidth / 2f,
    focalY: Float = canvasHeight / 2f,
  ): BoardViewport {
    val oldScale = fitScale() * zoom
    if (oldScale <= 0f) return this
    val logicalX = (focalX - originX()) / oldScale
    val logicalY = (focalY - originY()) / oldScale
    val newZoom = targetZoom.coerceIn(MIN_VIEWPORT_ZOOM, MAX_VIEWPORT_ZOOM)
    val newScale = fitScale() * newZoom
    val centeredOriginX = (canvasWidth - boardWidth * newScale) / 2f
    val centeredOriginY = (canvasHeight - boardHeight * newScale) / 2f
    val (px, py) = clampPan(
      focalX - centeredOriginX - logicalX * newScale,
      focalY - centeredOriginY - logicalY * newScale,
      newZoom,
    )
    return copy(zoom = newZoom, panX = px, panY = py)
  }

  /** Converts a canvas-pixel position to a board coordinate, clamped to the board bounds. */
  fun logicalPoint(screenX: Float, screenY: Float): LogicalPoint {
    val baseScale = fitScale()
    if (baseScale <= 0f) return LogicalPoint(0, 0)
    return LogicalPoint(
      x = ((screenX - originX()) / (baseScale * zoom)).toInt(),
      y = ((screenY - originY()) / (baseScale * zoom)).toInt(),
    ).clamped()
  }
}
