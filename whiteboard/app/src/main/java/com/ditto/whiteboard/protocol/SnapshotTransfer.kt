package com.ditto.whiteboard.protocol

import java.util.UUID

/** Hard protocol bounds. They are enforced before any peer-controlled allocation occurs. */
internal const val MAX_TRANSFER_BYTES: Long = 16L * 1024 * 1024
internal const val MAX_TRANSFER_CHUNKS: Int = 4_096
internal const val MAX_TRANSFER_ID_LENGTH: Int = 128
internal const val SHA_256_BYTE_COUNT: Int = 32

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
      require(bytes.isNotEmpty()) { "Transfer payload must not be empty" }
      require(bytes.size.toLong() <= MAX_TRANSFER_BYTES) {
        "Transfer exceeds the ${MAX_TRANSFER_BYTES / (1024 * 1024)} MiB protocol limit"
      }
      require(id.isNotBlank() && id.length <= MAX_TRANSFER_ID_LENGTH)
      val chunkCount = (bytes.size.toLong() + maxChunkSize - 1L) / maxChunkSize
      require(chunkCount <= MAX_TRANSFER_CHUNKS) { "Transfer requires too many chunks" }
      val chunks = buildList(chunkCount.toInt()) {
        var offset = 0
        while (offset < bytes.size) {
          val end = (offset + maxChunkSize).coerceAtMost(bytes.size)
          add(bytes.copyOfRange(offset, end))
          offset = end
        }
      }
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
  private var receivedByteCount = 0L
  private var sealed = false

  init {
    require(transferId.isNotBlank() && transferId.length <= MAX_TRANSFER_ID_LENGTH)
    require(chunkCount in 1..MAX_TRANSFER_CHUNKS)
    require(byteCount in 1..MAX_TRANSFER_BYTES)
    require(expectedDigest.size == SHA_256_BYTE_COUNT)
  }

  @Synchronized
  fun add(index: Int, bytes: ByteArray): SnapshotAssemblyResult {
    if (sealed) return SnapshotAssemblyResult.Rejected("Transfer already ended")
    if (index !in chunks.indices) return SnapshotAssemblyResult.Rejected("Chunk index out of range")
    val previousSize = chunks[index]?.size ?: 0
    val nextByteCount = receivedByteCount - previousSize + bytes.size
    if (nextByteCount > byteCount || nextByteCount > MAX_TRANSFER_BYTES) {
      return SnapshotAssemblyResult.Rejected("Transfer exceeds its declared byte count")
    }
    chunks[index] = bytes.copyOf()
    receivedByteCount = nextByteCount
    return SnapshotAssemblyResult.Pending
  }

  @Synchronized
  fun finish(id: String): SnapshotAssemblyResult {
    sealed = true
    if (id != transferId) return SnapshotAssemblyResult.Rejected("Transfer ID mismatch")
    if (chunks.any { it == null }) return SnapshotAssemblyResult.Rejected("Snapshot is incomplete")
    if (receivedByteCount != byteCount) return SnapshotAssemblyResult.Rejected("Snapshot size mismatch")
    val bytes = ByteArray(byteCount.toInt())
    var offset = 0
    chunks.filterNotNull().forEach { chunk ->
      chunk.copyInto(bytes, destinationOffset = offset)
      offset += chunk.size
    }
    if (!WhiteboardProtocol.sha256(bytes).contentEquals(expectedDigest)) {
      return SnapshotAssemblyResult.Rejected("Snapshot digest mismatch")
    }
    return SnapshotAssemblyResult.Complete(bytes)
  }
}
