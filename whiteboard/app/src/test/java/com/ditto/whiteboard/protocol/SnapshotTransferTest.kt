package com.ditto.whiteboard.protocol

import org.junit.Assert.assertArrayEquals
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
}
