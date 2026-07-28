package com.ditto.whiteboard.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
