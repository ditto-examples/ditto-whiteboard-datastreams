package com.ditto.whiteboard.domain

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
    if (state.operations.containsKey(operation.id)) return state
    val priorMax = state.operations.values.maxOfOrNull(BoardOperation::stamp)
    return if (priorMax == null || operation.stamp > priorMax) {
      applyNewest(state, operation)
    } else {
      rebuild(state.operations + (operation.id to operation))
    }
  }

  fun merge(state: BoardState, operations: Iterable<BoardOperation>): BoardState =
    rebuild(state.operations + operations.associateBy(BoardOperation::id))

  /**
   * Folds a single operation that is known to sort after every operation already in [state], so it
   * can be applied directly without a full [rebuild]. Because the operation is the newest, any clear
   * watermark is necessarily older than it, and its profile update supersedes any prior one.
   */
  private fun applyNewest(state: BoardState, operation: BoardOperation): BoardState {
    val operations = state.operations + (operation.id to operation)
    val highWaterMarks = state.highWaterMarks + (
      operation.id.senderPeerKey to maxOf(
        state.highWaterMarks[operation.id.senderPeerKey] ?: 0,
        operation.id.senderSequence,
      )
      )
    return when (operation) {
      is BoardOperation.ProfileUpdate -> state.copy(
        operations = operations,
        highWaterMarks = highWaterMarks,
        profiles = state.profiles + (operation.profile.peerKey to operation.profile),
      )
      is BoardOperation.Clear -> state.copy(
        operations = operations,
        highWaterMarks = highWaterMarks,
        objects = emptyMap(),
        erasures = emptyList(),
        clearWatermark = operation.stamp,
      )
      is BoardOperation.Commit -> state.copy(
        operations = operations,
        highWaterMarks = highWaterMarks,
        objects = state.objects + (operation.boardObject.id to operation.boardObject),
      )
      is BoardOperation.Erase -> state.copy(
        operations = operations,
        highWaterMarks = highWaterMarks,
        objects = BoardGeometry.erase(state.objects.values, operation).associateBy(BoardObject::id),
        erasures = state.erasures + operation,
      )
    }
  }

  fun rebuild(operations: Map<OperationId, BoardOperation>): BoardState {
    var objects = emptyMap<ObjectId, BoardObject>()
    var erasures = emptyList<BoardOperation.Erase>()
    var clearWatermark: OperationStamp? = null
    val profiles = mutableMapOf<String, Pair<OperationStamp, UserProfile>>()
    val highWaterMarks = mutableMapOf<String, Long>()

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
              objects = emptyMap()
              erasures = emptyList()
            }
          }
          is BoardOperation.Commit -> {
            if (clearWatermark == null || operation.stamp > clearWatermark) {
              objects = objects + (operation.boardObject.id to operation.boardObject)
            }
          }
          is BoardOperation.Erase -> {
            if (clearWatermark == null || operation.stamp > clearWatermark) {
              objects = BoardGeometry.erase(objects.values, operation).associateBy(BoardObject::id)
              erasures = erasures + operation
            }
          }
        }
      }

    return BoardState(
      objects = objects,
      erasures = erasures,
      clearWatermark = clearWatermark,
      profiles = profiles.mapValues { it.value.second },
      highWaterMarks = highWaterMarks,
      operations = operations,
    )
  }
}

class OperationClock(val peerKey: String) {
  private var senderSequence = 0L
  private var lamport = 0L

  @Synchronized
  fun next(): Pair<OperationId, OperationStamp> {
    senderSequence += 1
    lamport += 1
    return OperationId(peerKey, senderSequence) to OperationStamp(lamport, peerKey, senderSequence)
  }

  @Synchronized
  fun observe(stamp: OperationStamp) {
    lamport = maxOf(lamport, stamp.lamport) + 1
  }
}
