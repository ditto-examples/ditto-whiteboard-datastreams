package com.ditto.whiteboard.ui.board

import com.ditto.whiteboard.domain.BOARD_HEIGHT
import com.ditto.whiteboard.domain.BOARD_WIDTH
import com.ditto.whiteboard.domain.LogicalPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the camera math extracted out of [BoardCanvas]. */
class BoardViewportTest {
  @Test
  fun firstTallFrameInitializesDirectlyToFillWithoutLetterboxGutters() {
    val viewport = BoardViewport().resized(400, 1_000, initializeToFill = true)
    val renderedWidth = viewport.boardWidth * viewport.fitScale() * viewport.zoom
    val renderedHeight = viewport.boardHeight * viewport.fitScale() * viewport.zoom

    assertTrue(renderedWidth >= viewport.canvasWidth)
    assertTrue(renderedHeight >= viewport.canvasHeight)
  }

  // A canvas exactly the size of the board: fit scale is 1, so screen == board coordinates.
  private val exact = BoardViewport(canvasWidth = BOARD_WIDTH, canvasHeight = BOARD_HEIGHT)

  @Test fun fitScaleIsOneWhenCanvasMatchesBoard() {
    assertEquals(1f, exact.fitScale(), 1e-4f)
  }

  @Test fun logicalPointIsIdentityAtFitZoom() {
    assertEquals(LogicalPoint(0, 0), exact.logicalPoint(0f, 0f))
    assertEquals(LogicalPoint(1920, 1080), exact.logicalPoint(1920f, 1080f))
  }

  @Test fun logicalPointClampsOutsideTheBoard() {
    assertEquals(LogicalPoint(0, 0), exact.logicalPoint(-500f, -500f))
    val corner = exact.logicalPoint(99999f, 99999f)
    assertEquals(LogicalPoint(BOARD_WIDTH, BOARD_HEIGHT), corner)
  }

  @Test
  fun letterboxedGuttersAreNotDrawingSurface() {
    val wide = BoardViewport(canvasWidth = 4_000, canvasHeight = 1_000)
    assertTrue(wide.originX() > 0f)
    assertTrue(!wide.containsBoardPoint(0f, 500f))
    assertTrue(wide.containsBoardPoint(wide.originX() + 1f, 500f))
  }

  @Test fun zoomIsClampedToBounds() {
    assertEquals(MAX_VIEWPORT_ZOOM, exact.withZoom(1000f).zoom, 1e-4f)
    assertEquals(MIN_VIEWPORT_ZOOM, exact.withZoom(0f).zoom, 1e-4f)
  }

  @Test fun zoomKeepsTheFocalBoardPointFixed() {
    val focalX = 1920f
    val focalY = 1080f
    val before = exact.logicalPoint(focalX, focalY)
    val after = exact.withZoom(3f, focalX, focalY).logicalPoint(focalX, focalY)
    // The board point under the fingers should stay under the fingers (within a pixel of rounding).
    assertTrue(kotlin.math.abs(before.x - after.x) <= 2)
    assertTrue(kotlin.math.abs(before.y - after.y) <= 2)
  }

  @Test fun panIsClampedToTravelBounds() {
    // At fit zoom the board exactly fills the canvas, so there is no room to pan.
    assertEquals(0f, exact.panBy(5000f, 5000f).panX, 1e-4f)
    assertEquals(0f, exact.panBy(5000f, 5000f).panY, 1e-4f)

    // Zoomed to 2x the board is twice the canvas width, so max horizontal travel is a quarter-board.
    val zoomed = exact.withZoom(2f)
    val expectedTravel = (BOARD_WIDTH * zoomed.fitScale() * 2f - BOARD_WIDTH) / 2f
    assertEquals(expectedTravel, zoomed.panBy(1_000_000f, 0f).panX, 1f)
  }

  @Test fun fillZoomStaysWithinBoundsForAWideCanvas() {
    val wide = BoardViewport(canvasWidth = 4000, canvasHeight = 1800)
    val fill = wide.fillZoom()
    assertTrue(fill in MIN_VIEWPORT_ZOOM..MAX_VIEWPORT_ZOOM)
    // A canvas wider than 16:9 must zoom past 1 to cover the board with no letterboxing.
    assertTrue(fill > 1f)
  }
}
