package com.ditto.whiteboard.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardGeometryTest {
  @Test
  fun simplifyKeepsEndpointsAndRemovesCollinearNoise() {
    val points = (0..100).map { LogicalPoint(it, it) }
    val simplified = BoardGeometry.simplify(points)
    assertEquals(listOf(points.first(), points.last()), simplified)
  }

  @Test
  fun simplifyHandlesMaximumWireStrokeWithoutRecursion() {
    val points = (0 until 4_096).map { index ->
      LogicalPoint(index % BOARD_WIDTH, if (index % 2 == 0) 0 else BOARD_HEIGHT)
    }
    val simplified = BoardGeometry.simplify(points)
    assertEquals(points.first(), simplified.first())
    assertEquals(points.last(), simplified.last())
  }

  @Test
  fun eraserDeterministicallySplitsFreehand() {
    val id = OperationId("a", 1)
    val stamp = OperationStamp(1, "a", 1)
    val stroke = BoardObject.Freehand(
      ObjectId(id), stamp, 0xFF000000.toInt(), width = 2,
      points = listOf(LogicalPoint(0, 50), LogicalPoint(40, 50), LogicalPoint(60, 50), LogicalPoint(100, 50)),
    )
    val eraseId = OperationId("b", 1)
    val erase = BoardOperation.Erase(
      eraseId, OperationStamp(2, "b", 1),
      path = listOf(LogicalPoint(50, 30), LogicalPoint(50, 70)), radius = 3,
    )

    val first = BoardGeometry.erase(listOf(stroke), erase)
    val second = BoardGeometry.erase(listOf(stroke), erase)
    assertEquals(first, second)
    assertEquals(2, first.size)
    assertTrue(first.all { it is BoardObject.Freehand })
    assertFalse(first.map(BoardObject::id).contains(stroke.id))
  }

  @Test
  fun eraserRemovesIntersectedShapeInFull() {
    val id = OperationId("a", 1)
    val stamp = OperationStamp(1, "a", 1)
    val rectangle = BoardObject.Rectangle(
      ObjectId(id), stamp, 0xFF000000.toInt(),
      start = LogicalPoint(10, 10), end = LogicalPoint(100, 100),
    )
    val erase = BoardOperation.Erase(
      OperationId("b", 1), OperationStamp(2, "b", 1),
      path = listOf(LogicalPoint(50, 0), LogicalPoint(50, 30)), radius = 4,
    )
    assertTrue(BoardGeometry.erase(listOf(rectangle), erase).isEmpty())
  }

  @Test
  fun eraserRemovesSinglePointFreehandDot() {
    val id = OperationId("a", 1)
    val stamp = OperationStamp(1, "a", 1)
    val dot = BoardObject.Freehand(
      ObjectId(id),
      stamp,
      0xFF000000.toInt(),
      width = 4,
      points = listOf(LogicalPoint(50, 50)),
    )
    val erase = BoardOperation.Erase(
      OperationId("b", 1),
      OperationStamp(2, "b", 1),
      path = listOf(LogicalPoint(55, 50)),
      radius = 4,
    )

    assertTrue(BoardGeometry.erase(listOf(dot), erase).isEmpty())
  }

  @Test
  fun eraserSplitsACollinearTwoPointStrokeAtCapsuleBoundaries() {
    val id = OperationId("a", 1)
    val stamp = OperationStamp(1, "a", 1)
    val stroke = BoardObject.Freehand(
      ObjectId(id),
      stamp,
      0xFF000000.toInt(),
      width = 2,
      points = listOf(LogicalPoint(0, 50), LogicalPoint(100, 50)),
    )
    val erase = BoardOperation.Erase(
      OperationId("b", 1),
      OperationStamp(2, "b", 1),
      path = listOf(LogicalPoint(50, 40), LogicalPoint(50, 60)),
      radius = 5,
    )

    val fragments = BoardGeometry.erase(listOf(stroke), erase)
      .filterIsInstance<BoardObject.Freehand>()

    assertEquals(2, fragments.size)
    assertEquals(0, fragments.first().points.first().x)
    assertTrue(fragments.first().points.last().x in 43..45)
    assertTrue(fragments.last().points.first().x in 55..57)
    assertEquals(100, fragments.last().points.last().x)
  }

  @Test
  fun fragmentIdentityStaysFixedSizeAcrossDeepRepeatedErasure() {
    val root = ObjectId(OperationId("origin", 1))
    var current = root
    repeat(1_000) { depth ->
      val next = BoardGeometry.childObjectId(
        current,
        OperationId("eraser", depth + 1L),
        index = 1_000 + depth,
      )
      assertEquals(64, next.fragmentDigest.length)
      assertNotEquals(current.fragmentDigest, next.fragmentDigest)
      current = next
    }
    assertEquals(root.origin, current.origin)
  }

  @Test
  fun fragmentAmplificationNeverEvictsUnrelatedObjectsAtTheCap() {
    val sourceId = OperationId("source", 1)
    val sourceStamp = OperationStamp(1, "source", 1)
    val source = BoardObject.Freehand(
      ObjectId(sourceId),
      sourceStamp,
      0xFF000000.toInt(),
      width = 2,
      points = listOf(
        LogicalPoint(0, 100),
        LogicalPoint(100, 100),
        LogicalPoint(200, 100),
      ),
    )
    val unrelated = (1 until MAX_RENDERED_BOARD_OBJECTS).map { sequence ->
      val id = OperationId("other", sequence.toLong())
      val stamp = OperationStamp(sequence + 1L, "other", sequence.toLong())
      BoardObject.Line(
        ObjectId(id),
        stamp,
        0xFF000000.toInt(),
        start = LogicalPoint(1_000 + sequence, 0),
        end = LogicalPoint(1_000 + sequence, 10),
      )
    }
    val erase = BoardOperation.Erase(
      OperationId("eraser", 1),
      OperationStamp(MAX_RENDERED_BOARD_OBJECTS + 2L, "eraser", 1),
      path = listOf(LogicalPoint(100, 80), LogicalPoint(100, 120)),
      radius = 3,
    )

    val survivors = BoardGeometry.erase(listOf(source) + unrelated, erase)

    assertEquals(MAX_RENDERED_BOARD_OBJECTS, survivors.size)
    assertTrue(survivors.map(BoardObject::id).containsAll(unrelated.map(BoardObject::id)))
  }
}
