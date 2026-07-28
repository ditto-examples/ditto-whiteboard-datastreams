package com.ditto.whiteboard.protocol

import java.util.UUID

/**
 * A board snapshot split into size-bounded chunks for the Reliable `wb_state` stream, used when a
 * peer joins late and must catch up. [SnapshotTransfer.create] chunks the payload and records a
 * SHA-256 [digest]; the receiving [SnapshotAssembler] reassembles the chunks and verifies both the
 * byte count and the digest before the snapshot is accepted, so a corrupt or partial transfer is
 * rejected rather than merged.
 */
data class SnapshotTransfer(
  val id: String,
  val bytes: ByteArray,
  val digest: ByteArray,
  val chunks: List<ByteArray>,
) {
  companion object {
    fun create(bytes: ByteArray, maxChunkSize: Int, id: String = UUID.randomUUID().toString()): SnapshotTransfer {
      require(maxChunkSize > 0)
      val chunks = if (bytes.isEmpty()) listOf(byteArrayOf()) else bytes.asList().chunked(maxChunkSize).map { it.toByteArray() }
      return SnapshotTransfer(id, bytes, WhiteboardProtocol.sha256(bytes), chunks)
    }
  }
}

sealed interface SnapshotAssemblyResult {
  data object Pending : SnapshotAssemblyResult
  data class Complete(val bytes: ByteArray) : SnapshotAssemblyResult
  data class Rejected(val reason: String) : SnapshotAssemblyResult
}

class SnapshotAssembler(
  private val transferId: String,
  private val chunkCount: Int,
  private val byteCount: Long,
  private val expectedDigest: ByteArray,
) {
  private val chunks = arrayOfNulls<ByteArray>(chunkCount)

  init {
    require(chunkCount > 0)
    require(byteCount >= 0)
  }

  fun add(index: Int, bytes: ByteArray): SnapshotAssemblyResult {
    if (index !in chunks.indices) return SnapshotAssemblyResult.Rejected("Chunk index out of range")
    chunks[index] = bytes.copyOf()
    return SnapshotAssemblyResult.Pending
  }

  fun finish(id: String): SnapshotAssemblyResult {
    if (id != transferId) return SnapshotAssemblyResult.Rejected("Transfer ID mismatch")
    if (chunks.any { it == null }) return SnapshotAssemblyResult.Rejected("Snapshot is incomplete")
    val bytes = chunks.filterNotNull().fold(byteArrayOf()) { result, chunk -> result + chunk }
    if (bytes.size.toLong() != byteCount) return SnapshotAssemblyResult.Rejected("Snapshot size mismatch")
    if (!WhiteboardProtocol.sha256(bytes).contentEquals(expectedDigest)) {
      return SnapshotAssemblyResult.Rejected("Snapshot digest mismatch")
    }
    return SnapshotAssemblyResult.Complete(bytes)
  }
}
