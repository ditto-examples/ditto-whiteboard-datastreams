import Foundation
import Testing
@testable import WhiteboardCore

@Suite("SnapshotTransferTest")
struct SnapshotTransferTest {
  @Test func chunksReassembleAndValidateDigest() throws {
    let bytes = Data((0..<10_013).map { UInt8($0 % 251) })
    let transfer = try SnapshotTransfer.create(bytes, maxChunkSize: 1_024, id: "transfer")
    var assembler = try SnapshotAssembler(
      transferId: transfer.id,
      chunkCount: transfer.chunks.count,
      byteCount: Int64(bytes.count),
      expectedDigest: transfer.digest
    )
    for index in transfer.chunks.indices.reversed() {
      _ = assembler.add(index: index, bytes: transfer.chunks[index])
    }
    let result = assembler.finish("transfer")
    guard case .complete(let reassembled) = result else {
      Issue.record("Expected complete, got \(result)")
      return
    }
    #expect(reassembled == bytes)
  }

  @Test func corruptSnapshotIsRejected() throws {
    let transfer = try SnapshotTransfer.create(Data([1, 2, 3]), maxChunkSize: 2, id: "transfer")
    var assembler = try SnapshotAssembler(
      transferId: transfer.id, chunkCount: transfer.chunks.count, byteCount: 3,
      expectedDigest: transfer.digest
    )
    _ = assembler.add(index: 0, bytes: Data([9, 2]))
    _ = assembler.add(index: 1, bytes: Data([3]))
    guard case .rejected = assembler.finish("transfer") else {
      Issue.record("Expected rejection")
      return
    }
  }

  @Test func peerControlledAllocationBoundsAreRejectedBeforeAllocation() {
    let digest = Data(repeating: 0, count: sha256ByteCount)
    #expect(throws: WhiteboardCoreError.self) {
      try SnapshotAssembler(
        transferId: "transfer", chunkCount: maxTransferChunks + 1, byteCount: 1,
        expectedDigest: digest
      )
    }
    #expect(throws: WhiteboardCoreError.self) {
      try SnapshotAssembler(
        transferId: "transfer", chunkCount: 1, byteCount: maxTransferBytes + 1,
        expectedDigest: digest
      )
    }
    #expect(throws: WhiteboardCoreError.self) {
      try SnapshotAssembler(
        transferId: "transfer", chunkCount: 1, byteCount: 0, expectedDigest: digest
      )
    }
    #expect(throws: WhiteboardCoreError.self) {
      try SnapshotTransfer.create(Data(), maxChunkSize: 1)
    }
  }

  @Test func chunksCannotExceedDeclaredByteCount() throws {
    var assembler = try SnapshotAssembler(
      transferId: "transfer",
      chunkCount: 1,
      byteCount: 2,
      expectedDigest: WhiteboardProtocol.sha256(Data([1, 2]))
    )
    let result = assembler.add(index: 0, bytes: Data([1, 2, 3]))
    #expect(result == .rejected("Transfer exceeds its declared byte count"))
  }

  @Test func assemblerSealsAtomicallyWhenEndIsAccepted() throws {
    let bytes = Data([1, 2, 3])
    var assembler = try SnapshotAssembler(
      transferId: "transfer",
      chunkCount: 1,
      byteCount: Int64(bytes.count),
      expectedDigest: WhiteboardProtocol.sha256(bytes)
    )
    _ = assembler.add(index: 0, bytes: bytes)

    guard case .complete = assembler.finish("transfer") else {
      Issue.record("Expected complete")
      return
    }
    #expect(assembler.add(index: 0, bytes: Data([9, 9, 9])) == .rejected("Transfer already ended"))
  }
}
