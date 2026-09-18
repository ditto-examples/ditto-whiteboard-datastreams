package com.ditto.whiteboard.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The incremental fast path in [BoardReducer.apply] must produce exactly the same [BoardState] as a
 * full [BoardReducer.rebuild], regardless of the order operations arrive in. These tests lock that
 * equivalence so the optimization can't silently diverge from the authoritative fold.
 */
class BoardReducerIncrementalTest {
  private fun commit(peer: String, seq: Long, lamport: Long): BoardOperation.Commit {
    val id = OperationId(peer, seq)
    val stamp = OperationStamp(lamport, peer, seq)
    val objectId = ObjectId(id)
    val base = seq.toInt() * 10
    val obj = BoardObject.Freehand(
      id = objectId,
      stamp = stamp,
      colorArgb = 0xFF112233.toInt(),
      points = listOf(LogicalPoint(base, base), LogicalPoint(base + 50, base + 50)),
    )
    return BoardOperation.Commit(id, stamp, obj)
  }

  private fun clear(peer: String, seq: Long, lamport: Long) =
    BoardOperation.Clear(OperationId(peer, seq), OperationStamp(lamport, peer, seq))

  private fun erase(peer: String, seq: Long, lamport: Long, path: List<LogicalPoint>) =
    BoardOperation.Erase(OperationId(peer, seq), OperationStamp(lamport, peer, seq), path)

  private fun profile(peer: String, seq: Long, lamport: Long, name: String, color: Int) =
    BoardOperation.ProfileUpdate(OperationId(peer, seq), OperationStamp(lamport, peer, seq), UserProfile(peer, name, color))

  private fun applyAll(ops: List<BoardOperation>): BoardState =
    ops.fold(BoardState()) { state, op -> BoardReducer.apply(state, op) }

  private fun rebuilt(ops: List<BoardOperation>): BoardState =
    BoardReducer.rebuild(ops.associateBy(BoardOperation::id))

  @Test fun inOrderApplyMatchesRebuild() {
    val ops = listOf(commit("a", 1, 1), commit("a", 2, 2), commit("b", 1, 3), commit("a", 3, 4))
    assertEquals(rebuilt(ops), applyAll(ops))
  }

  @Test fun outOfOrderApplyMatchesRebuild() {
    val a1 = commit("a", 1, 1)
    val a2 = commit("a", 2, 5)
    val b1 = commit("b", 1, 3)
    // Apply newest first, then older ops (which must fall back to a full rebuild internally).
    val state = listOf(a2, b1, a1).fold(BoardState()) { s, op -> BoardReducer.apply(s, op) }
    assertEquals(rebuilt(listOf(a1, a2, b1)), state)
  }

  @Test fun clearWatermarkMatchesRebuild() {
    val ops = listOf(commit("a", 1, 1), clear("a", 2, 2), commit("a", 3, 3))
    val state = applyAll(ops)
    assertEquals(rebuilt(ops), state)
    // Only the commit after the clear survives.
    assertEquals(1, state.objects.size)
  }

  @Test fun duplicateApplyIsANoOp() {
    val a1 = commit("a", 1, 1)
    val once = BoardReducer.apply(BoardState(), a1)
    val twice = BoardReducer.apply(once, a1)
    assertEquals(once, twice)
  }

  @Test fun eraseMatchesRebuild() {
    // Commit a diagonal stroke, then erase across it — exercises the split-freehand path.
    val stroke = commit("a", 1, 1)
    val erase = erase("a", 2, 2, listOf(LogicalPoint(0, 35), LogicalPoint(100, 35)))
    val ops = listOf(stroke, erase)
    assertEquals(rebuilt(ops), applyAll(ops))
  }

  @Test fun profileUpdateSupersessionMatchesRebuild() {
    val ops = listOf(
      profile("a", 1, 1, "Ana", 0xFF111111.toInt()),
      profile("a", 2, 2, "Ana Lee", 0xFF222222.toInt()),
    )
    val state = applyAll(ops)
    assertEquals(rebuilt(ops), state)
    assertEquals("Ana Lee", state.profiles["a"]?.displayName)
  }

  @Test fun outOfOrderCommitBeforeClearIsSuppressed() {
    val early = commit("a", 1, 1)
    val clear = clear("a", 3, 3)
    val late = commit("a", 2, 2) // stamp 2 < clear's stamp 3, but arrives last → rebuild path
    val ordered = listOf(early, clear, late)
    val state = ordered.fold(BoardState()) { s, op -> BoardReducer.apply(s, op) }
    assertEquals(rebuilt(ordered), state)
    assertEquals(0, state.objects.size) // both commits precede the clear watermark
  }

  @Test fun manyReverseOrderedConcurrentCommitsMatchRebuild() {
    val operations = (1L..400L).map { sequence ->
      commit(
        peer = if (sequence % 2L == 0L) "a" else "b",
        seq = sequence,
        lamport = sequence,
      )
    }

    assertEquals(rebuilt(operations), applyAll(operations.reversed()))
  }

  @Test fun outOfOrderCommitReplaysOnlyLaterErasers() {
    val earlyErase = erase(
      "eraser",
      1,
      2,
      listOf(LogicalPoint(0, 900), LogicalPoint(1_000, 900)),
    )
    val concurrentStroke = commit("artist", 1, 3)
    val lateErase = erase(
      "eraser",
      2,
      5,
      listOf(LogicalPoint(0, 35), LogicalPoint(100, 35)),
    )
    val newest = profile("profile", 1, 6, "Riley", 0xFF334455.toInt())
    val arrivalOrder = listOf(earlyErase, lateErase, newest, concurrentStroke)

    assertEquals(rebuilt(arrivalOrder), applyAll(arrivalOrder))
  }
}
