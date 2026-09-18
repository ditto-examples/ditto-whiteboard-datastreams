package com.ditto.whiteboard.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import java.lang.StrictMath
import java.security.MessageDigest

private const val MAX_FRAGMENTS_PER_OBJECT_ERASE = 16

object BoardGeometry {
  fun evenlySample(points: List<LogicalPoint>, maximumPoints: Int): List<LogicalPoint> {
    require(maximumPoints >= 2)
    if (points.size <= maximumPoints) return points
    val last = points.lastIndex.toLong()
    return List(maximumPoints) { index ->
      points[(index.toLong() * last / (maximumPoints - 1)).toInt()]
    }
  }

  fun simplify(points: List<LogicalPoint>, tolerance: Double = 2.0): List<LogicalPoint> {
    if (points.size <= 2) return points.distinct()
    val keep = BooleanArray(points.size)
    keep[0] = true
    keep[points.lastIndex] = true

    // Iterative Ramer-Douglas-Peucker avoids overflowing the call stack on an adversarial or very
    // long stroke whose farthest point repeatedly falls near one edge of the remaining segment.
    val pending = ArrayDeque<Pair<Int, Int>>()
    pending.addLast(0 to points.lastIndex)
    while (pending.isNotEmpty()) {
      val (start, end) = pending.removeLast()
      if (end <= start + 1) continue
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
        pending.addLast(start to farthestIndex)
        pending.addLast(farthestIndex to end)
      }
    }

    return points.filterIndexed { index, _ -> keep[index] }
  }

  fun erase(objects: Collection<BoardObject>, eraser: BoardOperation.Erase): List<BoardObject> {
    // Materialize the eraser polyline once. Rebuilding this list for every object—and, for a
    // freehand, for every stroke edge—turned an O(objects × edges × eraser-edges) calculation into
    // the same asymptotic work plus millions of short-lived Pair/list allocations.
    val eraserSegments = pathSegments(eraser.path)
    val ordered = objects.sortedWith(boardObjectOrder)
    val result = ArrayList<BoardObject>(min(ordered.size, MAX_RENDERED_BOARD_OBJECTS))
    ordered.forEachIndexed { objectIndex, boardObject ->
      val survivors = when (boardObject) {
        is BoardObject.Freehand -> splitFreehand(boardObject, eraser, eraserSegments)
        else -> if (intersectsSegments(boardObject, eraserSegments, eraser.radius)) emptyList() else listOf(boardObject)
      }
      // Reserve one slot for every later source object so fragment amplification can only degrade
      // the touched stroke; it must never evict an unrelated object.
      val laterSourceCount = ordered.lastIndex - objectIndex
      val availableForCurrent =
        (MAX_RENDERED_BOARD_OBJECTS - result.size - laterSourceCount).coerceAtLeast(0)
      val retained = if (survivors.size <= availableForCurrent) {
        survivors
      } else {
        survivors
          .sortedWith(
            compareByDescending<BoardObject> {
              (it as? BoardObject.Freehand)?.points?.size ?: Int.MAX_VALUE
            }.then(boardObjectOrder),
          )
          .take(availableForCurrent)
          .sortedWith(boardObjectOrder)
      }
      result.addAll(retained)
    }
    return result
  }

  fun intersects(boardObject: BoardObject, eraserPath: List<LogicalPoint>, radius: Int): Boolean {
    if (eraserPath.isEmpty()) return false
    return intersectsSegments(boardObject, pathSegments(eraserPath), radius)
  }

  private fun intersectsSegments(
    boardObject: BoardObject,
    eraserSegments: List<Pair<LogicalPoint, LogicalPoint>>,
    radius: Int,
  ): Boolean {
    val expandedRadius = radius + objectHalfWidth(boardObject)
    return objectSegments(boardObject).any { (start, end) ->
      eraserSegments.any { (eraseStart, eraseEnd) ->
        segmentBoundsOverlap(start, end, eraseStart, eraseEnd, expandedRadius) &&
          segmentDistance(start, end, eraseStart, eraseEnd) <= expandedRadius
      }
    }
  }

  private fun splitFreehand(
    stroke: BoardObject.Freehand,
    eraser: BoardOperation.Erase,
    eraserSegments: List<Pair<LogicalPoint, LogicalPoint>>,
  ): List<BoardObject.Freehand> {
    if (eraserSegments.isEmpty()) return listOf(stroke)
    val cutoff = eraser.radius + stroke.width / 2.0
    if (stroke.points.size == 1) {
      val erased = eraserSegments.any { (start, end) ->
        pointToSegmentDistance(stroke.points.single(), start, end) <= cutoff
      }
      return if (erased) emptyList() else listOf(stroke)
    }
    val fragments = mutableListOf<List<LogicalPoint>>()
    var current = mutableListOf<LogicalPoint>()
    var touched = false
    for (index in 0 until stroke.points.lastIndex) {
      val start = stroke.points[index]
      val end = stroke.points[index + 1]
      val erased = erasedIntervals(start, end, eraserSegments, cutoff)
      if (erased.isEmpty()) {
        appendSegment(current, start, end)
        continue
      }
      touched = true
      var cursor = 0.0
      erased.forEach { interval ->
        if (interval.start > cursor) {
          appendSegment(
            current,
            pointAlong(start, end, cursor),
            pointAlong(start, end, interval.start),
          )
        }
        if (current.size >= 2) fragments += current
        current = mutableListOf()
        cursor = max(cursor, interval.end)
      }
      if (cursor < 1.0) {
        appendSegment(current, pointAlong(start, end, cursor), end)
      }
    }
    if (current.size >= 2) fragments += current
    if (!touched) return listOf(stroke)

    // A long stroke can alternate erased/surviving edges many times. Retain the
    // longest bounded subset so one operation cannot amplify the rendered object graph without
    // bound; original fragment indices keep identities deterministic on every peer.
    return fragments
      .mapIndexed { index, points -> index to points }
      .sortedWith(compareByDescending<Pair<Int, List<LogicalPoint>>> { it.second.size }.thenBy { it.first })
      .take(MAX_FRAGMENTS_PER_OBJECT_ERASE)
      .sortedBy { it.first }
      .map { (index, points) ->
      stroke.copy(
        id = childObjectId(stroke.id, eraser.id, index),
        points = points,
      )
      }
  }
  private data class Interval(val start: Double, val end: Double)

  /** Returns the exact parameter intervals of [start]→[end] inside any eraser-segment capsule. */
  private fun erasedIntervals(
    start: LogicalPoint,
    end: LogicalPoint,
    eraserSegments: List<Pair<LogicalPoint, LogicalPoint>>,
    radius: Double,
  ): List<Interval> {
    val intervals = eraserSegments
      .asSequence()
      .filter { (eraseStart, eraseEnd) ->
        segmentBoundsOverlap(start, end, eraseStart, eraseEnd, radius)
      }
      .flatMap { (eraseStart, eraseEnd) ->
        capsuleIntervals(start, end, eraseStart, eraseEnd, radius).asSequence()
      }
      .sortedBy(Interval::start)
      .toList()
    if (intervals.isEmpty()) return emptyList()
    val merged = mutableListOf<Interval>()
    intervals.forEach { interval ->
      val previous = merged.lastOrNull()
      if (previous == null || interval.start > previous.end) {
        merged += interval
      } else {
        merged[merged.lastIndex] = Interval(previous.start, max(previous.end, interval.end))
      }
    }
    return merged
  }

  private fun capsuleIntervals(
    segmentStart: LogicalPoint,
    segmentEnd: LogicalPoint,
    capsuleStart: LogicalPoint,
    capsuleEnd: LogicalPoint,
    radius: Double,
  ): List<Interval> {
    val intervals = mutableListOf<Interval>()
    circleInterval(segmentStart, segmentEnd, capsuleStart, radius)?.let(intervals::add)
    circleInterval(segmentStart, segmentEnd, capsuleEnd, radius)?.let(intervals::add)

    val vx = (capsuleEnd.x - capsuleStart.x).toDouble()
    val vy = (capsuleEnd.y - capsuleStart.y).toDouble()
    val lengthSquared = vx * vx + vy * vy
    if (lengthSquared > 0.0) {
      val dx = (segmentEnd.x - segmentStart.x).toDouble()
      val dy = (segmentEnd.y - segmentStart.y).toDouble()
      val relativeX = (segmentStart.x - capsuleStart.x).toDouble()
      val relativeY = (segmentStart.y - capsuleStart.y).toDouble()
      val projection = linearInterval(
        relativeX * vx + relativeY * vy,
        dx * vx + dy * vy,
        0.0,
        lengthSquared,
      )
      val strip = linearInterval(
        relativeX * vy - relativeY * vx,
        dx * vy - dy * vx,
        -radius * StrictMath.sqrt(lengthSquared),
        radius * StrictMath.sqrt(lengthSquared),
      )
      intersect(projection, strip)?.let(intervals::add)
    }
    return intervals
      .mapNotNull { intersect(it, Interval(0.0, 1.0)) }
      .filter { it.end - it.start > 1e-9 }
  }

  private fun circleInterval(
    segmentStart: LogicalPoint,
    segmentEnd: LogicalPoint,
    center: LogicalPoint,
    radius: Double,
  ): Interval? {
    val dx = (segmentEnd.x - segmentStart.x).toDouble()
    val dy = (segmentEnd.y - segmentStart.y).toDouble()
    val fx = (segmentStart.x - center.x).toDouble()
    val fy = (segmentStart.y - center.y).toDouble()
    val a = dx * dx + dy * dy
    if (a == 0.0) return if (fx * fx + fy * fy <= radius * radius) Interval(0.0, 1.0) else null
    val b = 2.0 * (fx * dx + fy * dy)
    val c = fx * fx + fy * fy - radius * radius
    val discriminant = b * b - 4.0 * a * c
    if (discriminant < 0.0) return null
    val root = StrictMath.sqrt(discriminant)
    return intersect(
      Interval((-b - root) / (2.0 * a), (-b + root) / (2.0 * a)),
      Interval(0.0, 1.0),
    )
  }

  private fun linearInterval(
    origin: Double,
    slope: Double,
    lower: Double,
    upper: Double,
  ): Interval? {
    if (slope == 0.0) return if (origin in lower..upper) Interval(0.0, 1.0) else null
    val first = (lower - origin) / slope
    val second = (upper - origin) / slope
    return Interval(min(first, second), max(first, second))
  }

  private fun intersect(first: Interval?, second: Interval?): Interval? {
    if (first == null || second == null) return null
    val start = max(first.start, second.start)
    val end = min(first.end, second.end)
    return if (start <= end) Interval(start, end) else null
  }

  private fun appendSegment(
    target: MutableList<LogicalPoint>,
    start: LogicalPoint,
    end: LogicalPoint,
  ) {
    if (target.lastOrNull() != start) target += start
    if (target.lastOrNull() != end) target += end
  }

  private fun pointAlong(start: LogicalPoint, end: LogicalPoint, parameter: Double): LogicalPoint =
    LogicalPoint(
      x = (start.x + (end.x - start.x) * parameter).roundToInt().coerceIn(0, BOARD_WIDTH),
      y = (start.y + (end.y - start.y) * parameter).roundToInt().coerceIn(0, BOARD_HEIGHT),
    )

  /**
   * Hashes the complete parent identity plus this erasure step into a fixed-size fragment id.
   * Lineage therefore remains collision-resistant without making repeated erasures copy an
   * ever-growing list or turn map hashing into O(erasure depth).
   */
  internal fun childObjectId(
    parent: ObjectId,
    eraserOperationId: OperationId,
    index: Int,
  ): ObjectId {
    require(index >= 0)
    val digest = MessageDigest.getInstance("SHA-256")
    digest.updateLengthPrefixed(parent.origin.senderPeerKey)
    digest.updateLong(parent.origin.senderSequence)
    digest.updateLengthPrefixed(parent.fragmentDigest)
    digest.updateLengthPrefixed(eraserOperationId.senderPeerKey)
    digest.updateLong(eraserOperationId.senderSequence)
    digest.updateLong(index.toLong())
    return parent.copy(fragmentDigest = digest.digest().toHex())
  }

  private val boardObjectOrder = compareBy<BoardObject>(
    { it.stamp },
    { it.id.origin.senderPeerKey },
    { it.id.origin.senderSequence },
    { it.id.fragmentDigest },
  )

  private fun MessageDigest.updateLengthPrefixed(value: String) {
    val bytes = value.encodeToByteArray()
    updateLong(bytes.size.toLong())
    update(bytes)
  }

  private fun MessageDigest.updateLong(value: Long) {
    for (shift in 56 downTo 0 step 8) update((value ushr shift).toByte())
  }

  private fun ByteArray.toHex(): String = buildString(size * 2) {
    this@toHex.forEach { byte ->
      val value = byte.toInt() and 0xff
      append("0123456789abcdef"[value ushr 4])
      append("0123456789abcdef"[value and 0x0f])
    }
  }

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
        x = (centerX + radiusX * StrictMath.cos(angle)).toInt(),
        y = (centerY + radiusY * StrictMath.sin(angle)).toInt(),
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
    if (dx == 0.0 && dy == 0.0) {
      return StrictMath.hypot(
        (point.x - start.x).toDouble(),
        (point.y - start.y).toDouble(),
      )
    }
    val t = (((point.x - start.x) * dx + (point.y - start.y) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
    return StrictMath.hypot(
      point.x - (start.x + t * dx),
      point.y - (start.y + t * dy),
    )
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

  private fun segmentBoundsOverlap(
    firstStart: LogicalPoint,
    firstEnd: LogicalPoint,
    secondStart: LogicalPoint,
    secondEnd: LogicalPoint,
    padding: Double,
  ): Boolean =
    max(firstStart.x, firstEnd.x) + padding >= min(secondStart.x, secondEnd.x) &&
      max(secondStart.x, secondEnd.x) + padding >= min(firstStart.x, firstEnd.x) &&
      max(firstStart.y, firstEnd.y) + padding >= min(secondStart.y, secondEnd.y) &&
      max(secondStart.y, secondEnd.y) + padding >= min(firstStart.y, firstEnd.y)

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
