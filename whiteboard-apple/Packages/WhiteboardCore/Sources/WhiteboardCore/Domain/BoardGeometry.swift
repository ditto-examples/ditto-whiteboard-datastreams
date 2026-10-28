import CryptoKit
import Foundation

public let maxFragmentsPerObjectErase: Int = 16

public enum BoardGeometry {
  public static func evenlySample(_ points: [LogicalPoint], maximumPoints: Int) -> [LogicalPoint] {
    precondition(maximumPoints >= 2)
    if points.count <= maximumPoints { return points }
    let last = Int64(points.count - 1)
    return (0..<maximumPoints).map { index in
      points[Int(Int64(index) * last / Int64(maximumPoints - 1))]
    }
  }

  public static func simplify(_ points: [LogicalPoint], tolerance: Double = 2.0) -> [LogicalPoint] {
    if points.count <= 2 {
      var seen = Set<LogicalPoint>()
      return points.filter { seen.insert($0).inserted }
    }
    var keep = [Bool](repeating: false, count: points.count)
    keep[0] = true
    keep[points.count - 1] = true

    var pending: [(Int, Int)] = [(0, points.count - 1)]
    while let (start, end) = pending.popLast() {
      if end <= start + 1 { continue }
      var farthestIndex = -1
      var farthestDistance = tolerance
      for index in (start + 1)..<end {
        let distance = pointToSegmentDistance(points[index], start: points[start], end: points[end])
        if distance > farthestDistance {
          farthestDistance = distance
          farthestIndex = index
        }
      }
      if farthestIndex >= 0 {
        keep[farthestIndex] = true
        pending.append((start, farthestIndex))
        pending.append((farthestIndex, end))
      }
    }

    return points.indices.filter { keep[$0] }.map { points[$0] }
  }

  public static func erase(
    _ objects: some Collection<BoardObject>, eraser: BoardOperation.Erase
  ) -> [BoardObject] {
    let eraserSegments = pathSegments(eraser.path)
    let ordered = objects.sorted(by: boardObjectOrder)
    var result: [BoardObject] = []
    result.reserveCapacity(min(ordered.count, maxRenderedBoardObjects))
    for (objectIndex, boardObject) in ordered.enumerated() {
      var survivors: [BoardObject]
      switch boardObject {
      case .freehand(let stroke):
        survivors = splitFreehand(stroke, eraser: eraser, eraserSegments: eraserSegments)
          .map { .freehand($0) }
      default:
        survivors = intersectsSegments(boardObject, eraserSegments: eraserSegments, radius: eraser.radius)
          ? []
          : [boardObject]
      }
      let laterSourceCount = ordered.count - 1 - objectIndex
      let availableForCurrent = max(maxRenderedBoardObjects - result.count - laterSourceCount, 0)
      if survivors.count > availableForCurrent {
        survivors = survivors
          .sorted { first, second in
            let firstCount = freehandPointCount(first)
            let secondCount = freehandPointCount(second)
            if firstCount != secondCount { return firstCount > secondCount }
            return boardObjectOrder(first, second)
          }
          .prefix(availableForCurrent)
          .sorted(by: boardObjectOrder)
      }
      result.append(contentsOf: survivors)
    }
    return result
  }

  public static func intersects(
    _ boardObject: BoardObject, eraserPath: [LogicalPoint], radius: Int
  ) -> Bool {
    if eraserPath.isEmpty { return false }
    return intersectsSegments(boardObject, eraserSegments: pathSegments(eraserPath), radius: radius)
  }

  private static func freehandPointCount(_ object: BoardObject) -> Int {
    if case .freehand(let stroke) = object { return stroke.points.count }
    return Int(Int32.max)
  }

  private static func intersectsSegments(
    _ boardObject: BoardObject, eraserSegments: [Segment], radius: Int
  ) -> Bool {
    let expandedRadius = Double(radius) + objectHalfWidth(boardObject)
    return objectSegments(boardObject).contains { segment in
      eraserSegments.contains { eraserSegment in
        segmentBoundsOverlap(segment.start, segment.end, eraserSegment.start, eraserSegment.end, padding: expandedRadius)
          && segmentDistance(segment.start, segment.end, eraserSegment.start, eraserSegment.end) <= expandedRadius
      }
    }
  }

  private static func splitFreehand(
    _ stroke: BoardObject.Freehand,
    eraser: BoardOperation.Erase,
    eraserSegments: [Segment]
  ) -> [BoardObject.Freehand] {
    if eraserSegments.isEmpty { return [stroke] }
    let cutoff = Double(eraser.radius) + Double(stroke.width) / 2.0
    if stroke.points.count == 1 {
      let point = stroke.points[0]
      let erased = eraserSegments.contains { segment in
        pointToSegmentDistance(point, start: segment.start, end: segment.end) <= cutoff
      }
      return erased ? [] : [stroke]
    }
    var fragments: [[LogicalPoint]] = []
    var current: [LogicalPoint] = []
    var touched = false
    for index in 0..<(stroke.points.count - 1) {
      let start = stroke.points[index]
      let end = stroke.points[index + 1]
      let erased = erasedIntervals(start, end: end, eraserSegments: eraserSegments, radius: cutoff)
      if erased.isEmpty {
        appendSegment(&current, start: start, end: end)
        continue
      }
      touched = true
      var cursor = 0.0
      for interval in erased {
        if interval.start > cursor {
          appendSegment(
            &current,
            start: pointAlong(start, end: end, parameter: cursor),
            end: pointAlong(start, end: end, parameter: interval.start)
          )
        }
        if current.count >= 2 { fragments.append(current) }
        current = []
        cursor = max(cursor, interval.end)
      }
      if cursor < 1.0 {
        appendSegment(&current, start: pointAlong(start, end: end, parameter: cursor), end: end)
      }
    }
    if current.count >= 2 { fragments.append(current) }
    if !touched { return [stroke] }

    return fragments.enumerated()
      .sorted { first, second in
        if first.element.count != second.element.count {
          return first.element.count > second.element.count
        }
        return first.offset < second.offset
      }
      .prefix(maxFragmentsPerObjectErase)
      .sorted { $0.offset < $1.offset }
      .map { index, points in
        var fragment = stroke
        fragment.id = childObjectId(stroke.id, eraserOperationId: eraser.id, index: index)
        fragment.points = points
        return fragment
      }
  }

  private struct Interval {
    var start: Double
    var end: Double
  }

  private static func erasedIntervals(
    _ start: LogicalPoint, end: LogicalPoint, eraserSegments: [Segment], radius: Double
  ) -> [Interval] {
    let intervals = eraserSegments
      .filter { segment in
        segmentBoundsOverlap(start, end, segment.start, segment.end, padding: radius)
      }
      .flatMap { segment in
        capsuleIntervals(start, segmentEnd: end, capsuleStart: segment.start, capsuleEnd: segment.end, radius: radius)
      }
      .sorted { $0.start < $1.start }
    if intervals.isEmpty { return [] }
    var merged: [Interval] = []
    for interval in intervals {
      if let previous = merged.last, interval.start <= previous.end {
        merged[merged.count - 1] = Interval(start: previous.start, end: max(previous.end, interval.end))
      } else {
        merged.append(interval)
      }
    }
    return merged
  }

  private static func capsuleIntervals(
    _ segmentStart: LogicalPoint,
    segmentEnd: LogicalPoint,
    capsuleStart: LogicalPoint,
    capsuleEnd: LogicalPoint,
    radius: Double
  ) -> [Interval] {
    var intervals: [Interval] = []
    if let interval = circleInterval(segmentStart, segmentEnd: segmentEnd, center: capsuleStart, radius: radius) {
      intervals.append(interval)
    }
    if let interval = circleInterval(segmentStart, segmentEnd: segmentEnd, center: capsuleEnd, radius: radius) {
      intervals.append(interval)
    }

    let vx = Double(capsuleEnd.x - capsuleStart.x)
    let vy = Double(capsuleEnd.y - capsuleStart.y)
    let lengthSquared = vx * vx + vy * vy
    if lengthSquared > 0.0 {
      let dx = Double(segmentEnd.x - segmentStart.x)
      let dy = Double(segmentEnd.y - segmentStart.y)
      let relativeX = Double(segmentStart.x - capsuleStart.x)
      let relativeY = Double(segmentStart.y - capsuleStart.y)
      let projection = linearInterval(
        origin: relativeX * vx + relativeY * vy,
        slope: dx * vx + dy * vy,
        lower: 0.0,
        upper: lengthSquared
      )
      let strip = linearInterval(
        origin: relativeX * vy - relativeY * vx,
        slope: dx * vy - dy * vx,
        lower: -radius * lengthSquared.squareRoot(),
        upper: radius * lengthSquared.squareRoot()
      )
      if let intersection = intersect(projection, strip) {
        intervals.append(intersection)
      }
    }
    return intervals
      .compactMap { intersect($0, Interval(start: 0.0, end: 1.0)) }
      .filter { $0.end - $0.start > 1e-9 }
  }

  private static func circleInterval(
    _ segmentStart: LogicalPoint, segmentEnd: LogicalPoint, center: LogicalPoint, radius: Double
  ) -> Interval? {
    let dx = Double(segmentEnd.x - segmentStart.x)
    let dy = Double(segmentEnd.y - segmentStart.y)
    let fx = Double(segmentStart.x - center.x)
    let fy = Double(segmentStart.y - center.y)
    let a = dx * dx + dy * dy
    if a == 0.0 {
      return fx * fx + fy * fy <= radius * radius ? Interval(start: 0.0, end: 1.0) : nil
    }
    let b = 2.0 * (fx * dx + fy * dy)
    let c = fx * fx + fy * fy - radius * radius
    let discriminant = b * b - 4.0 * a * c
    if discriminant < 0.0 { return nil }
    let root = discriminant.squareRoot()
    return intersect(
      Interval(start: (-b - root) / (2.0 * a), end: (-b + root) / (2.0 * a)),
      Interval(start: 0.0, end: 1.0)
    )
  }

  private static func linearInterval(
    origin: Double, slope: Double, lower: Double, upper: Double
  ) -> Interval? {
    if slope == 0.0 {
      return (lower...upper).contains(origin) ? Interval(start: 0.0, end: 1.0) : nil
    }
    let first = (lower - origin) / slope
    let second = (upper - origin) / slope
    return Interval(start: min(first, second), end: max(first, second))
  }

  private static func intersect(_ first: Interval?, _ second: Interval?) -> Interval? {
    guard let first, let second else { return nil }
    let start = max(first.start, second.start)
    let end = min(first.end, second.end)
    return start <= end ? Interval(start: start, end: end) : nil
  }

  private static func appendSegment(
    _ target: inout [LogicalPoint], start: LogicalPoint, end: LogicalPoint
  ) {
    if target.last != start { target.append(start) }
    if target.last != end { target.append(end) }
  }

  private static func pointAlong(
    _ start: LogicalPoint, end: LogicalPoint, parameter: Double
  ) -> LogicalPoint {
    LogicalPoint(
      x: kotlinRoundToInt(Double(start.x) + Double(end.x - start.x) * parameter)
        .clamped(to: 0...boardWidth),
      y: kotlinRoundToInt(Double(start.y) + Double(end.y - start.y) * parameter)
        .clamped(to: 0...boardHeight)
    )
  }

  public static func childObjectId(
    _ parent: ObjectId, eraserOperationId: OperationId, index: Int
  ) -> ObjectId {
    precondition(index >= 0)
    var digest = SHA256()
    digest.updateLengthPrefixed(parent.origin.senderPeerKey)
    digest.updateBigEndianInt64(parent.origin.senderSequence)
    digest.updateLengthPrefixed(parent.fragmentDigest)
    digest.updateLengthPrefixed(eraserOperationId.senderPeerKey)
    digest.updateBigEndianInt64(eraserOperationId.senderSequence)
    digest.updateBigEndianInt64(Int64(index))
    return ObjectId(
      origin: parent.origin,
      fragmentDigest: digest.finalize().map { String(format: "%02x", $0) }.joined()
    )
  }

  static func boardObjectOrder(_ first: BoardObject, _ second: BoardObject) -> Bool {
    if first.stamp != second.stamp { return first.stamp < second.stamp }
    if first.id.origin.senderPeerKey != second.id.origin.senderPeerKey {
      return first.id.origin.senderPeerKey < second.id.origin.senderPeerKey
    }
    if first.id.origin.senderSequence != second.id.origin.senderSequence {
      return first.id.origin.senderSequence < second.id.origin.senderSequence
    }
    return first.id.fragmentDigest < second.id.fragmentDigest
  }

  private static func objectHalfWidth(_ boardObject: BoardObject) -> Double {
    switch boardObject {
    case .freehand(let object): return Double(object.width) / 2.0
    case .line(let object): return Double(object.width) / 2.0
    case .rectangle(let object): return Double(object.width) / 2.0
    case .ellipse(let object): return Double(object.width) / 2.0
    case .text: return 0.0
    }
  }

  private struct Segment {
    var start: LogicalPoint
    var end: LogicalPoint
  }

  private static func objectSegments(_ boardObject: BoardObject) -> [Segment] {
    switch boardObject {
    case .freehand(let object):
      return pathSegments(object.points)
    case .line(let object):
      return [Segment(start: object.start, end: object.end)]
    case .rectangle(let object):
      let left = min(object.start.x, object.end.x)
      let top = min(object.start.y, object.end.y)
      let right = max(object.start.x, object.end.x)
      let bottom = max(object.start.y, object.end.y)
      let topLeft = LogicalPoint(x: left, y: top)
      let topRight = LogicalPoint(x: right, y: top)
      let bottomRight = LogicalPoint(x: right, y: bottom)
      let bottomLeft = LogicalPoint(x: left, y: bottom)
      return [
        Segment(start: topLeft, end: topRight),
        Segment(start: topRight, end: bottomRight),
        Segment(start: bottomRight, end: bottomLeft),
        Segment(start: bottomLeft, end: topLeft),
      ]
    case .ellipse(let object):
      return approximateEllipse(object.start, end: object.end)
    case .text(let object):
      let width = max(object.size, object.text.utf16.count * object.size * 3 / 5)
      let height = object.size * 5 / 4
      let topLeft = LogicalPoint(x: object.anchor.x, y: object.anchor.y - height)
      let topRight = LogicalPoint(x: object.anchor.x + width, y: object.anchor.y - height)
      let bottomRight = LogicalPoint(x: object.anchor.x + width, y: object.anchor.y)
      let bottomLeft = LogicalPoint(x: object.anchor.x, y: object.anchor.y)
      return [
        Segment(start: topLeft, end: topRight),
        Segment(start: topRight, end: bottomRight),
        Segment(start: bottomRight, end: bottomLeft),
        Segment(start: bottomLeft, end: topLeft),
      ]
    }
  }

  private static func approximateEllipse(_ start: LogicalPoint, end: LogicalPoint) -> [Segment] {
    let centerX = Double(start.x + end.x) / 2.0
    let centerY = Double(start.y + end.y) / 2.0
    let radiusX = Double(abs(end.x - start.x)) / 2.0
    let radiusY = Double(abs(end.y - start.y)) / 2.0
    let points = (0...32).map { index in
      let angle = Double.pi * 2.0 * Double(index) / 32.0
      return LogicalPoint(
        x: Int(centerX + radiusX * cos(angle)),
        y: Int(centerY + radiusY * sin(angle))
      )
    }
    return pathSegments(points)
  }

  private static func pathSegments(_ points: [LogicalPoint]) -> [Segment] {
    switch points.count {
    case 0:
      return []
    case 1:
      return [Segment(start: points[0], end: points[0])]
    default:
      return (0..<(points.count - 1)).map { Segment(start: points[$0], end: points[$0 + 1]) }
    }
  }

  private static func pointToSegmentDistance(
    _ point: LogicalPoint, start: LogicalPoint, end: LogicalPoint
  ) -> Double {
    let dx = Double(end.x - start.x)
    let dy = Double(end.y - start.y)
    if dx == 0.0 && dy == 0.0 {
      return Foundation.hypot(Double(point.x - start.x), Double(point.y - start.y))
    }
    let t = min(max(
      (Double(point.x - start.x) * dx + Double(point.y - start.y) * dy) / (dx * dx + dy * dy),
      0.0
    ), 1.0)
    return Foundation.hypot(
      Double(point.x) - (Double(start.x) + t * dx),
      Double(point.y) - (Double(start.y) + t * dy)
    )
  }

  private static func segmentDistance(
    _ a1: LogicalPoint, _ a2: LogicalPoint, _ b1: LogicalPoint, _ b2: LogicalPoint
  ) -> Double {
    if segmentsIntersect(a1, a2, b1, b2) { return 0.0 }
    return min(
      pointToSegmentDistance(a1, start: b1, end: b2),
      pointToSegmentDistance(a2, start: b1, end: b2),
      pointToSegmentDistance(b1, start: a1, end: a2),
      pointToSegmentDistance(b2, start: a1, end: a2)
    )
  }

  private static func segmentBoundsOverlap(
    _ firstStart: LogicalPoint, _ firstEnd: LogicalPoint,
    _ secondStart: LogicalPoint, _ secondEnd: LogicalPoint,
    padding: Double
  ) -> Bool {
    Double(max(firstStart.x, firstEnd.x)) + padding >= Double(min(secondStart.x, secondEnd.x))
      && Double(max(secondStart.x, secondEnd.x)) + padding >= Double(min(firstStart.x, firstEnd.x))
      && Double(max(firstStart.y, firstEnd.y)) + padding >= Double(min(secondStart.y, secondEnd.y))
      && Double(max(secondStart.y, secondEnd.y)) + padding >= Double(min(firstStart.y, firstEnd.y))
  }

  private static func segmentsIntersect(
    _ a: LogicalPoint, _ b: LogicalPoint, _ c: LogicalPoint, _ d: LogicalPoint
  ) -> Bool {
    func orientation(_ p: LogicalPoint, _ q: LogicalPoint, _ r: LogicalPoint) -> Int64 {
      Int64(q.y - p.y) * Int64(r.x - q.x) - Int64(q.x - p.x) * Int64(r.y - q.y)
    }
    func onSegment(_ p: LogicalPoint, _ q: LogicalPoint, _ r: LogicalPoint) -> Bool {
      (min(p.x, r.x)...max(p.x, r.x)).contains(q.x)
        && (min(p.y, r.y)...max(p.y, r.y)).contains(q.y)
    }

    let o1 = orientation(a, b, c)
    let o2 = orientation(a, b, d)
    let o3 = orientation(c, d, a)
    let o4 = orientation(c, d, b)
    if (o1 > 0) != (o2 > 0) && (o3 > 0) != (o4 > 0) { return true }
    return (o1 == 0 && onSegment(a, c, b))
      || (o2 == 0 && onSegment(a, d, b))
      || (o3 == 0 && onSegment(c, a, d))
      || (o4 == 0 && onSegment(c, b, d))
  }
}

private func kotlinRoundToInt(_ value: Double) -> Int {
  Int((value + 0.5).rounded(.down))
}

extension Comparable {
  fileprivate func clamped(to range: ClosedRange<Self>) -> Self {
    min(max(self, range.lowerBound), range.upperBound)
  }
}

extension SHA256 {
  fileprivate mutating func updateLengthPrefixed(_ value: String) {
    let bytes = Array(value.utf8)
    updateBigEndianInt64(Int64(bytes.count))
    update(data: bytes)
  }

  fileprivate mutating func updateBigEndianInt64(_ value: Int64) {
    var bytes = [UInt8]()
    bytes.reserveCapacity(8)
    for shift in stride(from: 56, through: 0, by: -8) {
      bytes.append(UInt8(truncatingIfNeeded: UInt64(bitPattern: value) >> shift))
    }
    update(data: bytes)
  }
}
