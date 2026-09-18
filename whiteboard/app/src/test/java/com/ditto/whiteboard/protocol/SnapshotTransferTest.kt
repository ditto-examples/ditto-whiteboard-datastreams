package com.ditto.whiteboard.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotTransferTest {
  @Test
  fun chunksReassembleAndValidateDigest() {
    val bytes = ByteArray(10_013) { (it % 251).toByte() }
    val transfer = SnapshotTransfer.create(bytes, maxChunkSize = 1_024, id = "transfer")
    val assembler = SnapshotAssembler(transfer.id, transfer.chunks.size, bytes.size.toLong(), transfer.digest)
    transfer.chunks.indices.reversed().forEach { assembler.add(it, transfer.chunks[it]) }
    val result = assembler.finish("transfer")
    assertTrue(result is SnapshotAssemblyResult.Complete)
    assertArrayEquals(bytes, (result as SnapshotAssemblyResult.Complete).bytes)
  }

  @Test
  fun corruptSnapshotIsRejected() {
    val transfer = SnapshotTransfer.create(byteArrayOf(1, 2, 3), maxChunkSize = 2, id = "transfer")
    val assembler = SnapshotAssembler(transfer.id, transfer.chunks.size, 3, transfer.digest)
    assembler.add(0, byteArrayOf(9, 2))
    assembler.add(1, byteArrayOf(3))
    assertTrue(assembler.finish("transfer") is SnapshotAssemblyResult.Rejected)
  }

  @Test
  fun peerControlledAllocationBoundsAreRejectedBeforeAllocation() {
    val digest = ByteArray(SHA_256_BYTE_COUNT)
    assertThrows(IllegalArgumentException::class.java) {
      SnapshotAssembler("transfer", MAX_TRANSFER_CHUNKS + 1, 1, digest)
    }
    assertThrows(IllegalArgumentException::class.java) {
      SnapshotAssembler("transfer", 1, MAX_TRANSFER_BYTES + 1, digest)
    }
    assertThrows(IllegalArgumentException::class.java) {
      SnapshotAssembler("transfer", 1, 0, digest)
    }
    assertThrows(IllegalArgumentException::class.java) {
      SnapshotTransfer.create(byteArrayOf(), maxChunkSize = 1)
    }
  }

  @Test
  fun chunksCannotExceedDeclaredByteCount() {
    val assembler = SnapshotAssembler(
      transferId = "transfer",
      chunkCount = 1,
      byteCount = 2,
      expectedDigest = WhiteboardProtocol.sha256(byteArrayOf(1, 2)),
    )
    val result = assembler.add(0, byteArrayOf(1, 2, 3))
    assertTrue(result is SnapshotAssemblyResult.Rejected)
    assertEquals("Transfer exceeds its declared byte count", (result as SnapshotAssemblyResult.Rejected).reason)
  }

  @Test
  fun assemblerSealsAtomicallyWhenEndIsAccepted() {
    val bytes = byteArrayOf(1, 2, 3)
    val assembler = SnapshotAssembler(
      transferId = "transfer",
      chunkCount = 1,
      byteCount = bytes.size.toLong(),
      expectedDigest = WhiteboardProtocol.sha256(bytes),
    )
    assembler.add(0, bytes)

    assertTrue(assembler.finish("transfer") is SnapshotAssemblyResult.Complete)
    assertEquals(
      SnapshotAssemblyResult.Rejected("Transfer already ended"),
      assembler.add(0, byteArrayOf(9, 9, 9)),
    )
  }
}
