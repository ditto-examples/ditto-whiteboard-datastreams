import Testing
import WhiteboardCore

@testable import Whiteboard

struct BoardViewportTests {
  @Test func `first resize initializes to fill`() {
    let viewport = BoardViewport().resized(width: 800, height: 600, initializeToFill: true)

    let fit = min(800.0 / 3840.0, 600.0 / 2160.0)
    let cover = max(800.0 / 3840.0, 600.0 / 2160.0)
    #expect(abs(viewport.zoom - cover / fit) < 0.0001)
    #expect(viewport.panX == 0)
    #expect(viewport.panY == 0)
  }

  @Test func `fit scale is the smaller ratio`() {
    let viewport = BoardViewport(canvasWidth: 800, canvasHeight: 600)
    #expect(abs(viewport.fitScale() - 800.0 / 3840.0) < 0.0001)
  }

  @Test func `zoom is clamped to min and max`() {
    let viewport = BoardViewport(canvasWidth: 800, canvasHeight: 600)
    #expect(viewport.withZoom(0.01).zoom == minViewportZoom)
    #expect(viewport.withZoom(100).zoom == maxViewportZoom)
  }

  @Test func `zoom about focal keeps board point fixed`() {
    let viewport = BoardViewport(canvasWidth: 800, canvasHeight: 600, zoom: 1)
    let focalX = 200.0
    let focalY = 150.0
    let before = viewport.logicalPoint(x: focalX, y: focalY)

    let zoomed = viewport.withZoom(2, focalX: focalX, focalY: focalY)
    let after = zoomed.logicalPoint(x: focalX, y: focalY)

    #expect(abs(after.x - before.x) <= 1)
    #expect(abs(after.y - before.y) <= 1)
  }

  @Test func `pan is clamped to travel bounds`() {
    let fitViewport = BoardViewport(canvasWidth: 800, canvasHeight: 600, zoom: 1)
    #expect(fitViewport.panBy(deltaX: 500, deltaY: 500) == fitViewport)

    let zoomed = BoardViewport(canvasWidth: 800, canvasHeight: 600, zoom: 4)
    let scale = zoomed.fitScale() * zoomed.zoom
    let horizontalTravel = (3840.0 * scale - 800) / 2
    let panned = zoomed.panBy(deltaX: 100_000, deltaY: -100_000)
    #expect(abs(panned.panX - horizontalTravel) < 0.0001)
    #expect(panned.panY < 0)
  }

  @Test func `logical point clamps to board bounds`() {
    let viewport = BoardViewport(canvasWidth: 800, canvasHeight: 600, zoom: 1)
    #expect(viewport.logicalPoint(x: -5_000, y: -5_000) == LogicalPoint(x: 0, y: 0))
    #expect(
      viewport.logicalPoint(x: 50_000, y: 50_000) == LogicalPoint(x: boardWidth, y: boardHeight)
    )
  }

  @Test func `contains board point reflects board rect`() {
    let viewport = BoardViewport(canvasWidth: 800, canvasHeight: 600, zoom: 1)
    #expect(viewport.containsBoardPoint(x: viewport.originX() + 1, y: viewport.originY() + 1))
    #expect(!viewport.containsBoardPoint(x: viewport.originX() - 10, y: viewport.originY() - 10))
  }

  @Test func `resize reclamps existing pan`() {
    var viewport = BoardViewport(canvasWidth: 800, canvasHeight: 600, zoom: 4)
    viewport = viewport.panBy(deltaX: 100_000, deltaY: 0)
    let travelBefore = viewport.panX
    let shrunk = viewport.resized(width: 400, height: 300, initializeToFill: false)
    let scale = shrunk.fitScale() * shrunk.zoom
    let expectedTravel = (3840.0 * scale - 400) / 2
    #expect(travelBefore > expectedTravel)
    #expect(abs(shrunk.panX - expectedTravel) < 0.0001)
  }
}
