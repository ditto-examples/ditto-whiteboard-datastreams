package com.ditto.whiteboard.domain

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * Folds the immutable operation log into a [BoardState] in Lamport total order.
 *
 * [apply] has an O(objects) fast path for the common case — an operation that is newer than
 * everything already applied is folded directly onto the current state, avoiding the full re-sort
 * and re-fold that [rebuild] performs. When an operation arrives out of order (e.g. a concurrent
 * remote edit with a smaller stamp), it falls back to [rebuild] so ordering stays correct. Both
 * paths produce identical state, so a peer converges regardless of the order operations arrive in.
 */
object BoardReducer {
  fun apply(state: BoardState, operation: BoardOperation): BoardState {
    state.operations[operation.id]?.let { existing ->
      val winner = canonicalOperation(existing, operation)
      return if (winner == existing) state else rebuild(state.operations.put(operation.id, winner))
    }
    val priorMax = state.latestStamp
    return if (priorMax == null || operation.stamp > priorMax) {
      applyNewest(state, operation)
    } else if (operation is BoardOperation.Commit) {
      applyOutOfOrderCommit(state, operation)
        ?: rebuild(state.operations + (operation.id to operation))
    } else {
      rebuild(state.operations + (operation.id to operation))
    }
  }

  fun merge(state: BoardState, operations: Iterable<BoardOperation>): BoardState {
    val merged = state.operations.toMutableMap()
    operations.forEach { operation ->
      merged[operation.id] = merged[operation.id]
        ?.let { existing -> canonicalOperation(existing, operation) }
        ?: operation
    }
    return rebuild(merged)
  }

  /**
   * Folds a single operation that is known to sort after every operation already in [state], so it
   * can be applied directly without a full [rebuild]. Because the operation is the newest, any clear
   * watermark is necessarily older than it, and its profile update supersedes any prior one.
   */
  private fun applyNewest(state: BoardState, operation: BoardOperation): BoardState {
    val operations = state.operations.put(operation.id, operation)
    val highWaterMarks = state.highWaterMarks.put(
      operation.id.senderPeerKey,
      maxOf(
        state.highWaterMarks[operation.id.senderPeerKey] ?: 0,
        operation.id.senderSequence,
      ),
    )
    return when (operation) {
      is BoardOperation.ProfileUpdate -> state.copy(
        operations = operations,
        highWaterMarks = highWaterMarks,
        latestStamp = operation.stamp,
        profiles = state.profiles.put(operation.profile.peerKey, operation.profile),
      )
      is BoardOperation.Clear -> state.copy(
        operations = operations,
        highWaterMarks = highWaterMarks,
        latestStamp = operation.stamp,
        objects = persistentMapOf(),
        erasures = persistentListOf(),
        clearWatermark = operation.stamp,
        renderCapacityReachedSinceClear = false,
      )
      is BoardOperation.Commit -> {
        val objects = if (state.objects.size < MAX_RENDERED_BOARD_OBJECTS) {
          state.objects.put(operation.boardObject.id, operation.boardObject)
        } else {
          state.objects
        }
        state.copy(
          operations = operations,
          highWaterMarks = highWaterMarks,
          latestStamp = operation.stamp,
          objects = objects,
          renderCapacityReachedSinceClear =
            state.renderCapacityReachedSinceClear ||
              objects.size >= MAX_RENDERED_BOARD_OBJECTS,
        )
      }
      is BoardOperation.Erase -> {
        val objects = BoardGeometry.erase(state.objects.values, operation)
          .associateBy(BoardObject::id)
          .toPersistentMap()
        state.copy(
          operations = operations,
          highWaterMarks = highWaterMarks,
          latestStamp = operation.stamp,
          objects = objects,
          erasures = state.erasures.add(operation),
          renderCapacityReachedSinceClear =
            state.renderCapacityReachedSinceClear ||
              objects.size >= MAX_RENDERED_BOARD_OBJECTS,
        )
      }
    }
  }

  /**
   * Inserts a concurrent commit without sorting the complete log when the render-cap admission
   * history cannot affect the result. A later eraser requires a full fold: its deterministic
   * fragment reservation considers every source object together, so replaying it against only the
   * newly inserted object could retain a different fragment set near the cap.
   */
  private fun applyOutOfOrderCommit(
    state: BoardState,
    operation: BoardOperation.Commit,
  ): BoardState? {
    val operations = state.operations.put(operation.id, operation)
    val highWaterMarks = state.highWaterMarks.put(
      operation.id.senderPeerKey,
      maxOf(
        state.highWaterMarks[operation.id.senderPeerKey] ?: 0,
        operation.id.senderSequence,
      ),
    )
    if (state.clearWatermark?.let { operation.stamp <= it } == true) {
      return state.copy(operations = operations, highWaterMarks = highWaterMarks)
    }
    if (
      state.renderCapacityReachedSinceClear ||
      state.erasures.any { it.stamp > operation.stamp } ||
      state.objects.size >= MAX_RENDERED_BOARD_OBJECTS
    ) return null

    val objects = state.objects.put(operation.boardObject.id, operation.boardObject)
    return state.copy(
      operations = operations,
      highWaterMarks = highWaterMarks,
      objects = objects,
      renderCapacityReachedSinceClear = objects.size >= MAX_RENDERED_BOARD_OBJECTS,
    )
  }

  fun rebuild(operations: Map<OperationId, BoardOperation>): BoardState {
    val objects = mutableMapOf<ObjectId, BoardObject>()
    val erasures = mutableListOf<BoardOperation.Erase>()
    var clearWatermark: OperationStamp? = null
    val profiles = mutableMapOf<String, Pair<OperationStamp, UserProfile>>()
    val highWaterMarks = mutableMapOf<String, Long>()
    var renderCapacityReachedSinceClear = false

    operations.values.sortedWith(compareBy<BoardOperation> { it.stamp }.thenBy { it.id.senderPeerKey }.thenBy { it.id.senderSequence })
      .forEach { operation ->
        highWaterMarks[operation.id.senderPeerKey] = maxOf(
          highWaterMarks[operation.id.senderPeerKey] ?: 0,
          operation.id.senderSequence,
        )
        when (operation) {
          is BoardOperation.ProfileUpdate -> {
            val current = profiles[operation.profile.peerKey]
            if (current == null || operation.stamp > current.first) {
              profiles[operation.profile.peerKey] = operation.stamp to operation.profile
            }
          }
          is BoardOperation.Clear -> {
            if (clearWatermark == null || operation.stamp > clearWatermark) {
              clearWatermark = operation.stamp
              objects.clear()
              erasures.clear()
              renderCapacityReachedSinceClear = false
            }
          }
          is BoardOperation.Commit -> {
            if (
              (clearWatermark == null || operation.stamp > clearWatermark) &&
              objects.size < MAX_RENDERED_BOARD_OBJECTS
            ) {
              objects[operation.boardObject.id] = operation.boardObject
            }
            if (objects.size >= MAX_RENDERED_BOARD_OBJECTS) {
              renderCapacityReachedSinceClear = true
            }
          }
          is BoardOperation.Erase -> {
            if (clearWatermark == null || operation.stamp > clearWatermark) {
              val survivors = BoardGeometry.erase(objects.values, operation)
              objects.clear()
              survivors.associateByTo(objects, BoardObject::id)
              erasures += operation
              if (objects.size >= MAX_RENDERED_BOARD_OBJECTS) {
                renderCapacityReachedSinceClear = true
              }
            }
          }
        }
      }

    return BoardState(
      objects = objects.toPersistentMap(),
      erasures = erasures.toPersistentList(),
      clearWatermark = clearWatermark,
      profiles = profiles.mapValues { it.value.second }.toPersistentMap(),
      highWaterMarks = highWaterMarks.toPersistentMap(),
      operations = operations.toPersistentMap(),
      latestStamp = operations.values.maxOfOrNull(BoardOperation::stamp),
      renderCapacityReachedSinceClear = renderCapacityReachedSinceClear,
    )
  }
}

class OperationClock(
  val peerKey: String,
  initialSenderSequence: Long = 0L,
  initialLamport: Long = initialSenderSequence,
) {
  private var senderSequence = initialSenderSequence
  private var lamport = initialLamport

  @Synchronized
  fun next(): Pair<OperationId, OperationStamp> {
    require(senderSequence < MAX_OPERATION_COUNTER && lamport < MAX_OPERATION_COUNTER) {
      "Operation clock space is exhausted"
    }
    senderSequence += 1
    lamport += 1
    return OperationId(peerKey, senderSequence) to OperationStamp(lamport, peerKey, senderSequence)
  }

  @Synchronized
  fun observe(stamp: OperationStamp) {
    // The next local event performs the Lamport increment. Incrementing both here and in next()
    // needlessly advanced twice per receive and made repeated snapshot observation poison the clock.
    lamport = maxOf(lamport, stamp.lamport)
  }
}
