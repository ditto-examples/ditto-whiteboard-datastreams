package com.ditto.whiteboard.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardReducerTest {
  @Test
  fun concurrentDeliveryOrdersConverge() {
    val operations = listOf(commit("a", 1, 1, 10), commit("b", 1, 1, 20), erase("c", 1, 2))
    val forward = operations.fold(BoardState(), BoardReducer::apply)
    val reverse = operations.reversed().fold(BoardState(), BoardReducer::apply)
    assertEquals(forward, reverse)
  }

  @Test
  fun duplicateOperationIsIdempotent() {
    val operation = commit("a", 1, 1, 10)
    val once = BoardReducer.apply(BoardState(), operation)
    val twice = BoardReducer.apply(once, operation)
    assertEquals(once, twice)
    assertEquals(1, twice.operations.size)
  }

  @Test
  fun clearSuppressesOlderDrawingButAllowsNewerDrawing() {
    val old = commit("a", 1, 1, 10)
    val clear = BoardOperation.Clear(OperationId("b", 1), OperationStamp(4, "b", 1))
    val newer = commit("a", 2, 5, 200)
    val state = BoardReducer.merge(BoardState(), listOf(newer, clear, old))
    assertFalse(state.objects.containsKey(old.boardObject.id))
    assertTrue(state.objects.containsKey(newer.boardObject.id))
    assertEquals(clear.stamp, state.clearWatermark)
  }

  @Test
  fun operationStampUsesStableTotalOrder() {
    val stamps = listOf(
      OperationStamp(2, "b", 1),
      OperationStamp(2, "a", 2),
      OperationStamp(1, "z", 5),
    ).sorted()
    assertEquals(listOf("z", "a", "b"), stamps.map(OperationStamp::peerKey))
  }

  @Test
  fun equivocatedOperationIdConvergesRegardlessOfDeliveryOrder() {
    val first = commit("a", 1, 1, 10)
    val second = commit("a", 1, 1, 200)

    val firstThenSecond = listOf(first, second).fold(BoardState(), BoardReducer::apply)
    val secondThenFirst = listOf(second, first).fold(BoardState(), BoardReducer::apply)

    assertEquals(firstThenSecond, secondThenFirst)
    assertEquals(canonicalOperation(first, second), firstThenSecond.operations[first.id])
  }

  private fun commit(peer: String, sequence: Long, lamport: Long, x: Int): BoardOperation.Commit {
    val id = OperationId(peer, sequence)
    val stamp = OperationStamp(lamport, peer, sequence)
    return BoardOperation.Commit(
      id, stamp,
      BoardObject.Line(ObjectId(id), stamp, 0xFF000000.toInt(), start = LogicalPoint(x, 0), end = LogicalPoint(x, 100)),
    )
  }

  private fun erase(peer: String, sequence: Long, lamport: Long): BoardOperation.Erase =
    BoardOperation.Erase(
      OperationId(peer, sequence), OperationStamp(lamport, peer, sequence),
      path = listOf(LogicalPoint(10, 50), LogicalPoint(10, 60)), radius = 2,
    )
}
