package com.ditto.whiteboard.domain

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

object BoardGeometry {
  fun simplify(points: List<LogicalPoint>, tolerance: Double = 2.0): List<LogicalPoint> {
    if (points.size <= 2) return points.distinct()
    val keep = BooleanArray(points.size)
    keep[0] = true
    keep[points.lastIndex] = true

    fun mark(start: Int, end: Int) {
      if (end <= start + 1) return
      var farthestIndex = -1
      var farthestDistance = tolerance
      for (index in start + 1 until end) {
        val distance = pointToSegmentDistance(points[index], points[start], points[end])
        if (distance > farthestDistance) {
          farthestDistance = distance
          farthestIndex = index
        }
      }
      if (farthestIndex >= 0) {
        keep[farthestIndex] = true
        mark(start, farthestIndex)
        mark(farthestIndex, end)
      }
    }

    mark(0, points.lastIndex)
    return points.filterIndexed { index, _ -> keep[index] }
  }

  fun erase(objects: Collection<BoardObject>, eraser: BoardOperation.Erase): List<BoardObject> =
    objects.flatMap { boardObject ->
      when (boardObject) {
        is BoardObject.Freehand -> splitFreehand(boardObject, eraser.path, eraser.radius)
        else -> if (intersects(boardObject, eraser.path, eraser.radius)) emptyList() else listOf(boardObject)
      }
    }

  fun intersects(boardObject: BoardObject, eraserPath: List<LogicalPoint>, radius: Int): Boolean {
    if (eraserPath.isEmpty()) return false
    val expandedRadius = radius + objectHalfWidth(boardObject)
    return objectSegments(boardObject).any { (start, end) ->
      pathSegments(eraserPath).any { (eraseStart, eraseEnd) ->
        segmentDistance(start, end, eraseStart, eraseEnd) <= expandedRadius
      }
    }
  }

  private fun splitFreehand(
    stroke: BoardObject.Freehand,
    eraserPath: List<LogicalPoint>,
    radius: Int,
  ): List<BoardObject.Freehand> {
    if (stroke.points.size < 2 || eraserPath.isEmpty()) return listOf(stroke)
    val cutoff = radius + stroke.width / 2.0
    val erasedEdges = BooleanArray(stroke.points.lastIndex)
    for (index in erasedEdges.indices) {
      erasedEdges[index] = pathSegments(eraserPath).any { (eraseStart, eraseEnd) ->
        segmentDistance(stroke.points[index], stroke.points[index + 1], eraseStart, eraseEnd) <= cutoff
      }
    }

    if (erasedEdges.none { it }) return listOf(stroke)
    val fragments = mutableListOf<List<LogicalPoint>>()
    var current = mutableListOf<LogicalPoint>()
    for (index in erasedEdges.indices) {
      if (!erasedEdges[index]) {
        if (current.isEmpty()) current += stroke.points[index]
        current += stroke.points[index + 1]
      } else if (current.size >= 2) {
        fragments += current
        current = mutableListOf()
      } else {
        current.clear()
      }
    }
    if (current.size >= 2) fragments += current

    return fragments.mapIndexed { index, points ->
      stroke.copy(
        id = stroke.id.copy(fragment = childFragment(stroke.id.fragment, index)),
        points = points,
      )
    }
  }

  /**
   * Derives a stable, deterministic fragment id for the [index]th piece a stroke splits into when
   * erased. Mixing the parent id with a prime keeps ids from colliding across repeated splits, so
   * every peer that replays the same erase produces identical fragment ids and converges.
   */
  private fun childFragment(parent: Int, index: Int): Int = parent * 1009 + index + 1

  private fun objectHalfWidth(boardObject: BoardObject): Double = when (boardObject) {
    is BoardObject.Freehand -> boardObject.width / 2.0
    is BoardObject.Line -> boardObject.width / 2.0
    is BoardObject.Rectangle -> boardObject.width / 2.0
    is BoardObject.Ellipse -> boardObject.width / 2.0
    is BoardObject.Text -> 0.0
  }

  private fun objectSegments(boardObject: BoardObject): List<Pair<LogicalPoint, LogicalPoint>> =
    when (boardObject) {
      is BoardObject.Freehand -> pathSegments(boardObject.points)
      is BoardObject.Line -> listOf(boardObject.start to boardObject.end)
      is BoardObject.Rectangle -> {
        val left = min(boardObject.start.x, boardObject.end.x)
        val top = min(boardObject.start.y, boardObject.end.y)
        val right = max(boardObject.start.x, boardObject.end.x)
        val bottom = max(boardObject.start.y, boardObject.end.y)
        val topLeft = LogicalPoint(left, top)
        val topRight = LogicalPoint(right, top)
        val bottomRight = LogicalPoint(right, bottom)
        val bottomLeft = LogicalPoint(left, bottom)
        listOf(topLeft to topRight, topRight to bottomRight, bottomRight to bottomLeft, bottomLeft to topLeft)
      }
      is BoardObject.Ellipse -> approximateEllipse(boardObject.start, boardObject.end)
      is BoardObject.Text -> {
        val width = max(boardObject.size, boardObject.text.length * boardObject.size * 3 / 5)
        val height = boardObject.size * 5 / 4
        val topLeft = LogicalPoint(boardObject.anchor.x, boardObject.anchor.y - height)
        val topRight = LogicalPoint(boardObject.anchor.x + width, boardObject.anchor.y - height)
        val bottomRight = LogicalPoint(boardObject.anchor.x + width, boardObject.anchor.y)
        val bottomLeft = LogicalPoint(boardObject.anchor.x, boardObject.anchor.y)
        listOf(topLeft to topRight, topRight to bottomRight, bottomRight to bottomLeft, bottomLeft to topLeft)
      }
    }

  private fun approximateEllipse(start: LogicalPoint, end: LogicalPoint): List<Pair<LogicalPoint, LogicalPoint>> {
    val centerX = (start.x + end.x) / 2.0
    val centerY = (start.y + end.y) / 2.0
    val radiusX = abs(end.x - start.x) / 2.0
    val radiusY = abs(end.y - start.y) / 2.0
    val points = (0..32).map { index ->
      val angle = Math.PI * 2.0 * index / 32.0
      LogicalPoint(
        x = (centerX + radiusX * kotlin.math.cos(angle)).toInt(),
        y = (centerY + radiusY * kotlin.math.sin(angle)).toInt(),
      )
    }
    return pathSegments(points)
  }

  private fun pathSegments(points: List<LogicalPoint>): List<Pair<LogicalPoint, LogicalPoint>> =
    when (points.size) {
      0 -> emptyList()
      1 -> listOf(points.first() to points.first())
      else -> points.zipWithNext()
    }

  private fun pointToSegmentDistance(
    point: LogicalPoint,
    start: LogicalPoint,
    end: LogicalPoint,
  ): Double {
    val dx = (end.x - start.x).toDouble()
    val dy = (end.y - start.y).toDouble()
    if (dx == 0.0 && dy == 0.0) return hypot((point.x - start.x).toDouble(), (point.y - start.y).toDouble())
    val t = (((point.x - start.x) * dx + (point.y - start.y) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
    return hypot(point.x - (start.x + t * dx), point.y - (start.y + t * dy))
  }

  private fun segmentDistance(
    a1: LogicalPoint,
    a2: LogicalPoint,
    b1: LogicalPoint,
    b2: LogicalPoint,
  ): Double {
    if (segmentsIntersect(a1, a2, b1, b2)) return 0.0
    return minOf(
      pointToSegmentDistance(a1, b1, b2),
      pointToSegmentDistance(a2, b1, b2),
      pointToSegmentDistance(b1, a1, a2),
      pointToSegmentDistance(b2, a1, a2),
    )
  }

  private fun segmentsIntersect(a: LogicalPoint, b: LogicalPoint, c: LogicalPoint, d: LogicalPoint): Boolean {
    fun orientation(p: LogicalPoint, q: LogicalPoint, r: LogicalPoint): Long =
      (q.y - p.y).toLong() * (r.x - q.x) - (q.x - p.x).toLong() * (r.y - q.y)
    fun onSegment(p: LogicalPoint, q: LogicalPoint, r: LogicalPoint): Boolean =
      q.x in min(p.x, r.x)..max(p.x, r.x) && q.y in min(p.y, r.y)..max(p.y, r.y)

    val o1 = orientation(a, b, c)
    val o2 = orientation(a, b, d)
    val o3 = orientation(c, d, a)
    val o4 = orientation(c, d, b)
    if ((o1 > 0) != (o2 > 0) && (o3 > 0) != (o4 > 0)) return true
    return (o1 == 0L && onSegment(a, c, b)) ||
      (o2 == 0L && onSegment(a, d, b)) ||
      (o3 == 0L && onSegment(c, a, d)) ||
      (o4 == 0L && onSegment(c, b, d))
  }
}
