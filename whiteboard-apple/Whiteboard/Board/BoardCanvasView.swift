import Anvil
import SwiftUI
import WhiteboardCore

#if os(macOS)
  import AppKit
#endif

private let gridMinorStep = 120
private let gridMajorStep = 480
private let remotePreviewAlpha = 0.55
private let localPreviewAlpha = 0.72
private let maxActiveStrokePoints = 4_096

private extension BoardTextFont {
  var fontDesign: Font.Design {
    switch self {
    case .system: return .default
    case .rounded: return .rounded
    case .serif: return .serif
    case .monospaced: return .monospaced
    }
  }
}

final class BoardRenderCache {
  private var cachedPaths: [ObjectId: (object: BoardObject, path: Path?)] = [:]

  func path(for object: BoardObject) -> Path? {
    if let entry = cachedPaths[object.id], entry.object == object { return entry.path }
    let path: Path?
    switch object {
    case .freehand(let freehand) where freehand.points.count >= 2:
      var built = Path()
      built.addLines(freehand.points.map { CGPoint(x: $0.x, y: $0.y) })
      path = built
    default:
      path = nil
    }
    cachedPaths[object.id] = (object, path)
    return path
  }

  func prune(keeping ids: Set<ObjectId>) {
    if cachedPaths.count != ids.count {
      cachedPaths = cachedPaths.filter { ids.contains($0.key) }
    }
  }
}

struct BoardCanvasView: View {
  @Binding var viewport: BoardViewport
  let objects: [ObjectId: BoardObject]
  let previews: [LivePreview]
  let tool: DrawingTool
  let colorArgb: Int32
  let editingEnabled: Bool
  let bannerMessage: String?
  let onPreview: (String, [LogicalPoint]) -> Void
  let onCommit: (String, [LogicalPoint]) -> Void

  @State private var viewportInitialized = false
  @State private var activePoints: [LogicalPoint] = []
  @State private var gestureId: String?
  @State private var drawing = false
  @State private var pinchStartZoom: Double?
  @State private var lastDragTranslation = CGSize.zero
  @State private var renderCache = BoardRenderCache()

  var body: some View {
    boardCanvas
      .background(Color(argb: Int32(bitPattern: 0xFF25_2A2D)))
      .overlay(alignment: .bottom) {
        if let bannerMessage {
          ConnectivityBanner(message: bannerMessage)
        }
      }
  }

  private var boardCanvas: some View {
    Canvas { context, size in
      draw(context: &context, size: size)
    }
    .accessibilityElement()
    .accessibilityIdentifier("boardCanvas")
    .accessibilityLabel(
      tool == .hand
        ? "Shared 3840 by 2160 drawing board. Hand tool active. Drag with one finger to pan and pinch to zoom."
        : "Shared 3840 by 2160 drawing board. Draw with one finger or a stylus. Pan and zoom with two fingers."
    )
    .onGeometryChange(for: CGSize.self, of: { $0.size }) { newSize in
      let initialize = !viewportInitialized && newSize.width > 0 && newSize.height > 0
      viewport = viewport.resized(
        width: Double(newSize.width),
        height: Double(newSize.height),
        initializeToFill: initialize
      )
      if initialize { viewportInitialized = true }
    }
    .gesture(drawDragGesture)
    .simultaneousGesture(magnifyGesture)
  }

  private var drawDragGesture: some Gesture {
    DragGesture(minimumDistance: 0)
      .onChanged { value in
        let delta = CGSize(
          width: value.translation.width - lastDragTranslation.width,
          height: value.translation.height - lastDragTranslation.height
        )
        lastDragTranslation = value.translation
        if pinchStartZoom != nil || optionPanEngaged {
          viewport = viewport.panBy(deltaX: Double(delta.width), deltaY: Double(delta.height))
          return
        }
        if tool == .hand {
          viewport = viewport.panBy(deltaX: Double(delta.width), deltaY: Double(delta.height))
          return
        }
        guard editingEnabled else { return }
        if gestureId == nil {
          gestureId = UUID().uuidString
          drawing = viewport.containsBoardPoint(
            x: Double(value.startLocation.x),
            y: Double(value.startLocation.y)
          )
          activePoints =
            drawing
            ? [
              viewport.logicalPoint(
                x: Double(value.startLocation.x),
                y: Double(value.startLocation.y)
              )
            ] : []
          if drawing, let gestureId { onPreview(gestureId, activePoints) }
        }
        guard drawing, let gestureId else { return }
        let point = viewport.logicalPoint(
          x: Double(value.location.x),
          y: Double(value.location.y)
        )
        switch tool {
        case .pen, .eraser:
          if activePoints.last != point {
            activePoints.append(point)
            if activePoints.count > maxActiveStrokePoints {
              activePoints = activePoints.enumerated().compactMap { index, element in
                index == 0 || index == activePoints.count - 1 || index % 2 == 0 ? element : nil
              }
            }
          }
        case .line, .rectangle, .ellipse, .text:
          if activePoints.isEmpty {
            activePoints = [point, point]
          } else if activePoints.count == 1 {
            activePoints.append(point)
          } else {
            activePoints[1] = point
          }
        case .hand:
          return
        }
        onPreview(gestureId, activePoints)
      }
      .onEnded { _ in
        if pinchStartZoom == nil, drawing, !activePoints.isEmpty, let gestureId {
          onCommit(gestureId, activePoints)
        }
        gestureId = nil
        drawing = false
        activePoints = []
        lastDragTranslation = .zero
      }
  }

  private var optionPanEngaged: Bool {
    #if os(macOS)
      NSEvent.modifierFlags.contains(.option)
    #else
      false
    #endif
  }

  private var magnifyGesture: some Gesture {
    MagnifyGesture()
      .onChanged { value in
        if pinchStartZoom == nil {
          pinchStartZoom = viewport.zoom
          drawing = false
          activePoints = []
        }
        guard let startZoom = pinchStartZoom else { return }
        viewport = viewport.withZoom(startZoom * Double(value.magnification))
      }
      .onEnded { _ in
        pinchStartZoom = nil
      }
  }

  private func draw(context: inout GraphicsContext, size: CGSize) {
    let scale = viewport.fitScale() * viewport.zoom
    guard scale > 0 else { return }
    let boardRect = CGRect(
      x: viewport.originX(),
      y: viewport.originY(),
      width: viewport.boardWidth * scale,
      height: viewport.boardHeight * scale
    )
    let visible = boardRect.intersection(CGRect(origin: .zero, size: size))
    if !visible.isEmpty {
      context.fill(
        Path(visible),
        with: .color(Color(red: 1, green: 254.0 / 255, blue: 252.0 / 255))
      )
    }
    var board = context
    board.clip(to: Path(visible))
    board.translateBy(x: boardRect.origin.x, y: boardRect.origin.y)
    board.scaleBy(x: scale, y: scale)
    drawGrid(context: &board, scale: scale)
    renderCache.prune(keeping: Set(objects.keys))
    for object in objects.values.sorted(by: { $0.stamp < $1.stamp }) {
      drawObject(object, context: &board)
    }
    for preview in previews {
      drawPreview(
        tool: preview.tool,
        points: preview.points,
        color: Color(argb: preview.colorArgb),
        alpha: remotePreviewAlpha,
        context: &board
      )
    }
    if !activePoints.isEmpty {
      drawPreview(
        tool: tool,
        points: activePoints,
        color: Color(argb: colorArgb),
        alpha: localPreviewAlpha,
        context: &board
      )
    }
    context.stroke(
      Path(boardRect),
      with: .color(Color(argb: Int32(bitPattern: 0xFF89_9197))),
      lineWidth: 1.5
    )
  }

  private func drawGrid(context: inout GraphicsContext, scale: Double) {
    let minorColor = Color(argb: Int32(bitPattern: 0xFFDC_E3E8))
    let majorColor = Color(argb: Int32(bitPattern: 0xFFBC_C8D0))
    let boardW = Int(viewport.boardWidth)
    let boardH = Int(viewport.boardHeight)
    for y in stride(from: 0, through: boardH, by: gridMinorStep) {
      for x in stride(from: 0, through: boardW, by: gridMinorStep) {
        let major = x % gridMajorStep == 0 && y % gridMajorStep == 0
        let radius = (major ? 1.8 : 1.1) / scale
        let rect = CGRect(
          x: Double(x) - radius,
          y: Double(y) - radius,
          width: radius * 2,
          height: radius * 2
        )
        context.fill(Path(ellipseIn: rect), with: .color(major ? majorColor : minorColor))
      }
    }
  }

  private func drawObject(_ object: BoardObject, context: inout GraphicsContext) {
    let color = Color(argb: object.colorArgb)
    switch object {
    case .freehand(let freehand):
      if freehand.points.count == 1, let point = freehand.points.first {
        let radius = Double(freehand.width) / 2
        context.fill(
          Path(
            ellipseIn: CGRect(
              x: Double(point.x) - radius,
              y: Double(point.y) - radius,
              width: radius * 2,
              height: radius * 2
            )
          ),
          with: .color(color)
        )
      } else if let path = renderCache.path(for: object) {
        context.stroke(
          path,
          with: .color(color),
          style: StrokeStyle(
            lineWidth: Double(freehand.width),
            lineCap: .round,
            lineJoin: .round
          )
        )
      }
    case .line(let line):
      var path = Path()
      path.move(to: CGPoint(x: line.start.x, y: line.start.y))
      path.addLine(to: CGPoint(x: line.end.x, y: line.end.y))
      context.stroke(
        path,
        with: .color(color),
        style: StrokeStyle(lineWidth: Double(line.width), lineCap: .round)
      )
    case .rectangle(let rectangle):
      context.stroke(
        Path(normalizedRect(from: rectangle.start, to: rectangle.end)),
        with: .color(color),
        lineWidth: Double(rectangle.width)
      )
    case .ellipse(let ellipse):
      context.stroke(
        Path(ellipseIn: normalizedRect(from: ellipse.start, to: ellipse.end)),
        with: .color(color),
        lineWidth: Double(ellipse.width)
      )
    case .text(let text):
      context.draw(
        Text(text.text)
          .font(.system(size: Double(text.size), design: text.font.fontDesign))
          .foregroundStyle(color),
        at: CGPoint(x: text.anchor.x, y: text.anchor.y),
        anchor: .bottomLeading
      )
    }
  }

  private func drawPreview(
    tool: DrawingTool,
    points: [LogicalPoint],
    color: Color,
    alpha: Double,
    context: inout GraphicsContext
  ) {
    guard !points.isEmpty else { return }
    switch tool {
    case .pen:
      strokePolyline(points, color: color.opacity(alpha), width: Double(defaultStrokeWidth), context: &context)
    case .eraser:
      strokePolyline(
        points,
        color: Color(argb: Int32(bitPattern: 0xFFE0_5252)).opacity(alpha),
        width: Double(defaultEraserRadius * 2),
        context: &context
      )
    case .line:
      guard let first = points.first, let last = points.last, points.count > 1 else { return }
      var path = Path()
      path.move(to: CGPoint(x: first.x, y: first.y))
      path.addLine(to: CGPoint(x: last.x, y: last.y))
      context.stroke(
        path,
        with: .color(color.opacity(alpha)),
        style: StrokeStyle(lineWidth: Double(defaultStrokeWidth), lineCap: .round)
      )
    case .rectangle:
      guard let first = points.first, let last = points.last, points.count > 1 else { return }
      context.stroke(
        Path(normalizedRect(from: first, to: last)),
        with: .color(color.opacity(alpha)),
        lineWidth: Double(defaultStrokeWidth)
      )
    case .ellipse:
      guard let first = points.first, let last = points.last, points.count > 1 else { return }
      context.stroke(
        Path(ellipseIn: normalizedRect(from: first, to: last)),
        with: .color(color.opacity(alpha)),
        lineWidth: Double(defaultStrokeWidth)
      )
    case .text:
      guard let first = points.first else { return }
      context.fill(
        Path(
          ellipseIn: CGRect(
            x: Double(first.x) - 12,
            y: Double(first.y) - 12,
            width: 24,
            height: 24
          )
        ),
        with: .color(color.opacity(alpha))
      )
    case .hand:
      return
    }
  }

  private func strokePolyline(
    _ points: [LogicalPoint],
    color: Color,
    width: Double,
    context: inout GraphicsContext
  ) {
    if points.count == 1, let point = points.first {
      let radius = width / 2
      context.fill(
        Path(
          ellipseIn: CGRect(
            x: Double(point.x) - radius,
            y: Double(point.y) - radius,
            width: width,
            height: width
          )
        ),
        with: .color(color)
      )
      return
    }
    guard points.count >= 2 else { return }
    var path = Path()
    path.addLines(points.map { CGPoint(x: $0.x, y: $0.y) })
    context.stroke(
      path,
      with: .color(color),
      style: StrokeStyle(lineWidth: width, lineCap: .round, lineJoin: .round)
    )
  }

  private func normalizedRect(from start: LogicalPoint, to end: LogicalPoint) -> CGRect {
    CGRect(
      x: Double(min(start.x, end.x)),
      y: Double(min(start.y, end.y)),
      width: Double(abs(end.x - start.x)),
      height: Double(abs(end.y - start.y))
    )
  }
}

private struct ConnectivityBanner: View {
  let message: String
  @Environment(\.dittoColors) private var colors

  var body: some View {
    Text(message)
      .font(.footnote)
      .lineLimit(3)
      .multilineTextAlignment(.center)
      .padding(.horizontal, 12)
      .padding(.vertical, 8)
      .background(
        colors.fillWarningSecondary.opacity(0.96),
        in: RoundedRectangle(cornerRadius: 12, style: .continuous)
      )
      .frame(maxWidth: 560)
      .padding(12)
  }
}
