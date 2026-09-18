package com.ditto.whiteboard.transport

import com.ditto.whiteboard.domain.BoardOperation
import com.ditto.whiteboard.domain.BoardState
import com.ditto.whiteboard.domain.OperationId
import com.ditto.whiteboard.domain.OperationStamp
import com.ditto.whiteboard.domain.UserProfile
import com.ditto.whiteboard.domain.canonicalOperation
import com.ditto.whiteboard.protocol.MAX_ERASE_OPERATIONS
import com.ditto.whiteboard.protocol.MAX_SNAPSHOT_OPERATIONS
import com.ditto.whiteboard.protocol.MAX_STATE_VECTOR_PEERS
import com.ditto.whiteboard.protocol.MAX_TRANSFER_BYTES
import com.ditto.whiteboard.protocol.SHA_256_BYTE_COUNT
import com.ditto.whiteboard.protocol.WhiteboardProtocol
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.toPersistentMap

internal sealed interface KnownOperationResult {
  data object Added : KnownOperationResult
  data object Replaced : KnownOperationResult
  data object Duplicate : KnownOperationResult
  data class Rejected(val reason: String) : KnownOperationResult
}

internal data class KnownStateMaterial(
  val state: BoardState,
  val version: Long,
  val digest: ByteArray,
)

internal data class KnownStateCapture(
  val state: BoardState,
  val version: Long,
)

/**
 * The single authoritative, bounded operation log used by every transport implementation.
 *
 * It owns canonical equivocation resolution, author/operation/erase/byte budgets, and the
 * incremental reconciliation digest. BoardSession applies an operation only after this log accepts
 * it, so local preview and nearby mesh have identical terminal behavior.
 */
internal class BoundedOperationLog {
  private val lock = Any()
  private var state = BoardState()
  private var operationJsonBytes = 0L
  private var eraseCount = 0
  private var version = 0L
  private val digestXor = ByteArray(SHA_256_BYTE_COUNT)
  private val profiles = mutableMapOf<String, Pair<OperationStamp, UserProfile>>()

  fun accept(operation: BoardOperation): KnownOperationResult = synchronized(lock) {
    state.operations[operation.id]?.let { existing ->
      val winner = canonicalOperation(existing, operation)
      if (winner == existing) return@synchronized KnownOperationResult.Duplicate
      val nextEraseCount =
        eraseCount -
          (if (existing is BoardOperation.Erase) 1 else 0) +
          (if (winner is BoardOperation.Erase) 1 else 0)
      if (nextEraseCount > MAX_ERASE_OPERATIONS) {
        return@synchronized KnownOperationResult.Rejected("Session erase-operation limit reached")
      }
      val nextJsonBytes = operationJsonBytes -
        WhiteboardProtocol.operationJsonByteCount(existing) +
        WhiteboardProtocol.operationJsonByteCount(winner)
      if (
        WhiteboardProtocol.snapshotDocumentByteCount(nextJsonBytes, state.operations.size) >
        MAX_TRANSFER_BYTES
      ) {
        return@synchronized KnownOperationResult.Rejected("Session snapshot byte limit reached")
      }
      state = rebuild(state.operations.put(operation.id, winner))
      operationJsonBytes = nextJsonBytes
      eraseCount = nextEraseCount
      WhiteboardProtocol.xorDigestInto(digestXor, WhiteboardProtocol.operationStateDigest(existing))
      WhiteboardProtocol.xorDigestInto(digestXor, WhiteboardProtocol.operationStateDigest(winner))
      version += 1
      return@synchronized KnownOperationResult.Replaced
    }
    if (state.operations.size >= MAX_SNAPSHOT_OPERATIONS) {
      return@synchronized KnownOperationResult.Rejected("Session operation limit reached")
    }
    if (operation is BoardOperation.Erase && eraseCount >= MAX_ERASE_OPERATIONS) {
      return@synchronized KnownOperationResult.Rejected("Session erase-operation limit reached")
    }
    if (
      operation.id.senderPeerKey !in state.highWaterMarks &&
      state.highWaterMarks.size >= MAX_STATE_VECTOR_PEERS
    ) {
      return@synchronized KnownOperationResult.Rejected("Session author limit reached")
    }
    val operationBytes = WhiteboardProtocol.operationJsonByteCount(operation)
    val nextCount = state.operations.size + 1
    val nextJsonBytes = operationJsonBytes + operationBytes
    if (
      WhiteboardProtocol.snapshotDocumentByteCount(nextJsonBytes, nextCount) >
      MAX_TRANSFER_BYTES
    ) {
      return@synchronized KnownOperationResult.Rejected("Session snapshot byte limit reached")
    }
    if (operation is BoardOperation.ProfileUpdate) {
      val current = profiles[operation.profile.peerKey]
      if (current == null || operation.stamp > current.first) {
        profiles[operation.profile.peerKey] = operation.stamp to operation.profile
      }
    }
    state = state.copy(
      operations = state.operations.put(operation.id, operation),
      profiles = profiles.mapValues { it.value.second }.toPersistentMap(),
      highWaterMarks = state.highWaterMarks.put(
        operation.id.senderPeerKey,
        maxOf(state.highWaterMarks[operation.id.senderPeerKey] ?: 0L, operation.id.senderSequence),
      ),
      latestStamp = maxOf(state.latestStamp ?: operation.stamp, operation.stamp),
    )
    operationJsonBytes = nextJsonBytes
    if (operation is BoardOperation.Erase) eraseCount += 1
    WhiteboardProtocol.xorDigestInto(digestXor, WhiteboardProtocol.operationStateDigest(operation))
    version += 1
    KnownOperationResult.Added
  }

  /** Atomically validates and merges a snapshot plus operations buffered behind it. */
  fun merge(operations: List<BoardOperation>): String? = synchronized(lock) {
    val uniqueIncoming = LinkedHashMap<OperationId, BoardOperation>()
    operations.forEach { operation ->
      uniqueIncoming[operation.id] = uniqueIncoming[operation.id]
        ?.let { prior -> canonicalOperation(prior, operation) }
        ?: operation
    }

    var jsonByteDelta = 0L
    var additionalCount = 0
    var eraseCountDelta = 0
    var changed = false
    val digestXorDelta = ByteArray(SHA_256_BYTE_COUNT)
    uniqueIncoming.values.forEach { operation ->
      val existing = state.operations[operation.id]
      if (existing != null) {
        val winner = canonicalOperation(existing, operation)
        if (winner != existing) {
          changed = true
          jsonByteDelta += WhiteboardProtocol.operationJsonByteCount(winner) -
            WhiteboardProtocol.operationJsonByteCount(existing)
          uniqueIncoming[operation.id] = winner
          eraseCountDelta +=
            (if (winner is BoardOperation.Erase) 1 else 0) -
            (if (existing is BoardOperation.Erase) 1 else 0)
          WhiteboardProtocol.xorDigestInto(
            digestXorDelta,
            WhiteboardProtocol.operationStateDigest(existing),
          )
          WhiteboardProtocol.xorDigestInto(
            digestXorDelta,
            WhiteboardProtocol.operationStateDigest(winner),
          )
        }
      } else {
        changed = true
        jsonByteDelta += WhiteboardProtocol.operationJsonByteCount(operation)
        additionalCount += 1
        if (operation is BoardOperation.Erase) eraseCountDelta += 1
        WhiteboardProtocol.xorDigestInto(
          digestXorDelta,
          WhiteboardProtocol.operationStateDigest(operation),
        )
      }
    }

    val nextCount = state.operations.size + additionalCount
    if (nextCount > MAX_SNAPSHOT_OPERATIONS) {
      return@synchronized "Merged snapshot exceeds the session operation limit"
    }
    if (eraseCount + eraseCountDelta > MAX_ERASE_OPERATIONS) {
      return@synchronized "Merged snapshot exceeds the session erase-operation limit"
    }
    val nextAuthors = (
      state.highWaterMarks.keys + uniqueIncoming.keys.map(OperationId::senderPeerKey)
      ).toSet().size
    if (nextAuthors > MAX_STATE_VECTOR_PEERS) {
      return@synchronized "Merged snapshot exceeds the session author limit"
    }
    val nextJsonBytes = operationJsonBytes + jsonByteDelta
    if (
      WhiteboardProtocol.snapshotDocumentByteCount(nextJsonBytes, nextCount) >
      MAX_TRANSFER_BYTES
    ) {
      return@synchronized "Merged snapshot exceeds the session byte limit"
    }
    if (!changed) return@synchronized null

    val merged = state.operations.builder()
    uniqueIncoming.values.forEach { operation ->
      merged[operation.id] = state.operations[operation.id]
        ?.let { existing -> canonicalOperation(existing, operation) }
        ?: operation
    }
    state = rebuild(merged.build())
    operationJsonBytes = nextJsonBytes
    eraseCount += eraseCountDelta
    WhiteboardProtocol.xorDigestInto(digestXor, digestXorDelta)
    version += 1
    null
  }

  fun material(): KnownStateMaterial = synchronized(lock) {
    KnownStateMaterial(
      state = state,
      version = version,
      digest = WhiteboardProtocol.stateDigestFromAccumulator(
        state.operations.size,
        digestXor.copyOf(),
      ),
    )
  }

  fun capture(): KnownStateCapture = synchronized(lock) { KnownStateCapture(state, version) }

  fun isCurrent(candidateVersion: Long): Boolean = synchronized(lock) { version == candidateVersion }

  fun profile(peerKey: String): UserProfile? = synchronized(lock) { state.profiles[peerKey] }

  private fun rebuild(operations: PersistentMap<OperationId, BoardOperation>): BoardState {
    val highWaterMarks = mutableMapOf<String, Long>()
    var latestStamp: OperationStamp? = null
    profiles.clear()
    operations.values.forEach { operation ->
      highWaterMarks[operation.id.senderPeerKey] = maxOf(
        highWaterMarks[operation.id.senderPeerKey] ?: 0L,
        operation.id.senderSequence,
      )
      latestStamp = maxOf(latestStamp ?: operation.stamp, operation.stamp)
      if (operation is BoardOperation.ProfileUpdate) {
        val current = profiles[operation.profile.peerKey]
        if (current == null || operation.stamp > current.first) {
          profiles[operation.profile.peerKey] = operation.stamp to operation.profile
        }
      }
    }
    return BoardState(
      operations = operations,
      profiles = profiles.mapValues { it.value.second }.toPersistentMap(),
      highWaterMarks = highWaterMarks.toPersistentMap(),
      latestStamp = latestStamp,
    )
  }
}
