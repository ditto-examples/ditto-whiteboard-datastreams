import Foundation
import WhiteboardCore

let minViewportZoom = 0.75
let maxViewportZoom = 12.0

struct BoardViewport: Equatable, Sendable {
  var canvasWidth = 0.0
  var canvasHeight = 0.0
  var zoom = 1.0
  var panX = 0.0
  var panY = 0.0
  var boardWidth = Double(WhiteboardCore.boardWidth)
  var boardHeight = Double(WhiteboardCore.boardHeight)

  func resized(width: Double, height: Double, initializeToFill: Bool) -> BoardViewport {
    var measured = self
    measured.canvasWidth = width
    measured.canvasHeight = height
    if initializeToFill && width > 0 && height > 0 {
      measured.zoom = measured.fillZoom()
      measured.panX = 0
      measured.panY = 0
    }
    return measured.withConstrainedPan()
  }

  func fitScale() -> Double {
    min(canvasWidth / boardWidth, canvasHeight / boardHeight)
  }

  func fillZoom() -> Double {
    let fit = fitScale()
    if fit <= 0 { return 1 }
    let cover = max(canvasWidth / boardWidth, canvasHeight / boardHeight)
    return min(max(cover / fit, minViewportZoom), maxViewportZoom)
  }

  func originX(atZoom: Double? = nil, atPanX: Double? = nil) -> Double {
    (canvasWidth - boardWidth * fitScale() * (atZoom ?? zoom)) / 2 + (atPanX ?? panX)
  }

  func originY(atZoom: Double? = nil, atPanY: Double? = nil) -> Double {
    (canvasHeight - boardHeight * fitScale() * (atZoom ?? zoom)) / 2 + (atPanY ?? panY)
  }

  private func clampPan(_ candidateX: Double, _ candidateY: Double, atZoom: Double) -> (
    Double, Double
  ) {
    let scale = fitScale() * atZoom
    if scale <= 0 { return (0, 0) }
    let horizontalTravel = max(0, (boardWidth * scale - canvasWidth) / 2)
    let verticalTravel = max(0, (boardHeight * scale - canvasHeight) / 2)
    return (
      min(max(candidateX, -horizontalTravel), horizontalTravel),
      min(max(candidateY, -verticalTravel), verticalTravel)
    )
  }

  func withConstrainedPan() -> BoardViewport {
    let (px, py) = clampPan(panX, panY, atZoom: zoom)
    var copy = self
    copy.panX = px
    copy.panY = py
    return copy
  }

  func panBy(deltaX: Double, deltaY: Double) -> BoardViewport {
    let (px, py) = clampPan(panX + deltaX, panY + deltaY, atZoom: zoom)
    var copy = self
    copy.panX = px
    copy.panY = py
    return copy
  }

  func withZoom(_ targetZoom: Double, focalX: Double? = nil, focalY: Double? = nil) -> BoardViewport
  {
    let focalX = focalX ?? canvasWidth / 2
    let focalY = focalY ?? canvasHeight / 2
    let oldScale = fitScale() * zoom
    if oldScale <= 0 { return self }
    let logicalX = (focalX - originX()) / oldScale
    let logicalY = (focalY - originY()) / oldScale
    let newZoom = min(max(targetZoom, minViewportZoom), maxViewportZoom)
    let newScale = fitScale() * newZoom
    let centeredOriginX = (canvasWidth - boardWidth * newScale) / 2
    let centeredOriginY = (canvasHeight - boardHeight * newScale) / 2
    let (px, py) = clampPan(
      focalX - centeredOriginX - logicalX * newScale,
      focalY - centeredOriginY - logicalY * newScale,
      atZoom: newZoom
    )
    var copy = self
    copy.zoom = newZoom
    copy.panX = px
    copy.panY = py
    return copy
  }

  func containsBoardPoint(x: Double, y: Double) -> Bool {
    let scale = fitScale() * zoom
    if scale <= 0 { return false }
    return (originX()...(originX() + boardWidth * scale)).contains(x)
      && (originY()...(originY() + boardHeight * scale)).contains(y)
  }

  func logicalPoint(x: Double, y: Double) -> LogicalPoint {
    let baseScale = fitScale()
    if baseScale <= 0 { return LogicalPoint(x: 0, y: 0) }
    return LogicalPoint(
      x: Int((x - originX()) / (baseScale * zoom)),
      y: Int((y - originY()) / (baseScale * zoom))
    ).clamped()
  }
}
