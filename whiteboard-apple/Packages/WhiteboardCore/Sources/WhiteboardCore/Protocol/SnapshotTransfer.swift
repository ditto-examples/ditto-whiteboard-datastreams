import Foundation

public let maxTransferBytes: Int64 = 16 * 1024 * 1024
public let maxTransferChunks: Int = 4_096
public let maxTransferIdLength: Int = 128
public let sha256ByteCount: Int = 32

public struct SnapshotTransfer: Equatable, Sendable {
  public let id: String
  public let bytes: Data
  public let digest: Data
  public let chunks: [Data]

  public static func create(
    _ bytes: Data,
    maxChunkSize: Int,
    id: String = UUID().uuidString.lowercased()
  ) throws -> SnapshotTransfer {
    func require(_ condition: Bool, _ message: String = "") throws {
      if !condition { throw WhiteboardCoreError.requirementFailed(message) }
    }
    try require(maxChunkSize > 0)
    try require(!bytes.isEmpty, "Transfer payload must not be empty")
    try require(
      Int64(bytes.count) <= maxTransferBytes,
      "Transfer exceeds the \(maxTransferBytes / (1024 * 1024)) MiB protocol limit"
    )
    try require(!id.isBlankKotlin && id.utf16.count <= maxTransferIdLength)
    let chunkCount = (Int64(bytes.count) + Int64(maxChunkSize) - 1) / Int64(maxChunkSize)
    try require(chunkCount <= Int64(maxTransferChunks), "Transfer requires too many chunks")
    var chunks: [Data] = []
    chunks.reserveCapacity(Int(chunkCount))
    var offset = 0
    while offset < bytes.count {
      let end = min(offset + maxChunkSize, bytes.count)
      chunks.append(bytes[offset..<end])
      offset = end
    }
    return SnapshotTransfer(id: id, bytes: bytes, digest: WhiteboardProtocol.sha256(bytes), chunks: chunks)
  }

  public init(id: String, bytes: Data, digest: Data, chunks: [Data]) {
    self.id = id
    self.bytes = bytes
    self.digest = digest
    self.chunks = chunks
  }
}

public enum SnapshotAssemblyResult: Equatable, Sendable {
  case pending
  case complete(Data)
  case rejected(String)
}

public struct SnapshotAssembler: Sendable {
  private let transferId: String
  private let chunkCount: Int
  private let byteCount: Int64
  private let expectedDigest: Data
  private var chunks: [Data?]
  private var receivedByteCount: Int64 = 0
  private var sealed = false

  public init(transferId: String, chunkCount: Int, byteCount: Int64, expectedDigest: Data) throws {
    func require(_ condition: Bool, _ message: String = "") throws {
      if !condition { throw WhiteboardCoreError.requirementFailed(message) }
    }
    try require(!transferId.isBlankKotlin && transferId.utf16.count <= maxTransferIdLength)
    try require((1...maxTransferChunks).contains(chunkCount))
    try require((1...maxTransferBytes).contains(byteCount))
    try require(expectedDigest.count == sha256ByteCount)
    self.transferId = transferId
    self.chunkCount = chunkCount
    self.byteCount = byteCount
    self.expectedDigest = expectedDigest
    self.chunks = [Data?](repeating: nil, count: chunkCount)
  }

  public mutating func add(index: Int, bytes: Data) -> SnapshotAssemblyResult {
    if sealed { return .rejected("Transfer already ended") }
    guard chunks.indices.contains(index) else { return .rejected("Chunk index out of range") }
    let previousSize = chunks[index]?.count ?? 0
    let nextByteCount = receivedByteCount - Int64(previousSize) + Int64(bytes.count)
    if nextByteCount > byteCount || nextByteCount > maxTransferBytes {
      return .rejected("Transfer exceeds its declared byte count")
    }
    chunks[index] = bytes
    receivedByteCount = nextByteCount
    return .pending
  }

  public mutating func finish(_ id: String) -> SnapshotAssemblyResult {
    sealed = true
    if id != transferId { return .rejected("Transfer ID mismatch") }
    if chunks.contains(where: { $0 == nil }) { return .rejected("Snapshot is incomplete") }
    if receivedByteCount != byteCount { return .rejected("Snapshot size mismatch") }
    var bytes = Data()
    bytes.reserveCapacity(Int(byteCount))
    for chunk in chunks {
      if let chunk { bytes.append(chunk) }
    }
    if WhiteboardProtocol.sha256(bytes) != expectedDigest {
      return .rejected("Snapshot digest mismatch")
    }
    return .complete(bytes)
  }
}
