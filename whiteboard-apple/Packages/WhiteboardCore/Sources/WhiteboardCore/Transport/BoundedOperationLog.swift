import Foundation

public enum KnownOperationResult: Equatable, Sendable {
  case added
  case replaced
  case duplicate
  case rejected(String)
}

public struct KnownStateMaterial: Equatable, Sendable {
  public let state: BoardState
  public let version: Int64
  public let digest: Data
}

public struct KnownStateCapture: Equatable, Sendable {
  public let state: BoardState
  public let version: Int64
}

public struct BoundedOperationLog: Sendable {
  private var state = BoardState()
  private var operationJsonBytes: Int64 = 0
  private var eraseCount = 0
  private var version: Int64 = 0
  private var digestXor = Data(repeating: 0, count: sha256ByteCount)
  private var profiles: [String: (OperationStamp, UserProfile)] = [:]

  public init() {}

  @discardableResult
  public mutating func accept(_ operation: BoardOperation) -> KnownOperationResult {
    if let existing = state.operations[operation.id] {
      let winner = canonicalOperation(existing, operation)
      if winner == existing { return .duplicate }
      var nextEraseCount = eraseCount
      if case .erase = existing { nextEraseCount -= 1 }
      if case .erase = winner { nextEraseCount += 1 }
      if nextEraseCount > WhiteboardProtocol.maxEraseOperations {
        return .rejected("Session erase-operation limit reached")
      }
      let nextJsonBytes = operationJsonBytes
        - Int64(WhiteboardProtocol.operationJsonByteCount(existing))
        + Int64(WhiteboardProtocol.operationJsonByteCount(winner))
      if WhiteboardProtocol.snapshotDocumentByteCount(
        operationJsonBytes: nextJsonBytes, operationCount: state.operations.count
      ) > maxTransferBytes {
        return .rejected("Session snapshot byte limit reached")
      }
      state = rebuild(state.operations.merging([operation.id: winner]) { _, new in new })
      operationJsonBytes = nextJsonBytes
      eraseCount = nextEraseCount
      WhiteboardProtocol.xorDigestInto(&digestXor, WhiteboardProtocol.operationStateDigest(existing))
      WhiteboardProtocol.xorDigestInto(&digestXor, WhiteboardProtocol.operationStateDigest(winner))
      version += 1
      return .replaced
    }
    if state.operations.count >= WhiteboardProtocol.maxSnapshotOperations {
      return .rejected("Session operation limit reached")
    }
    if case .erase = operation, eraseCount >= WhiteboardProtocol.maxEraseOperations {
      return .rejected("Session erase-operation limit reached")
    }
    if state.highWaterMarks[operation.id.senderPeerKey] == nil
      && state.highWaterMarks.count >= WhiteboardProtocol.maxStateVectorPeers
    {
      return .rejected("Session author limit reached")
    }
    let operationBytes = WhiteboardProtocol.operationJsonByteCount(operation)
    let nextCount = state.operations.count + 1
    let nextJsonBytes = operationJsonBytes + Int64(operationBytes)
    if WhiteboardProtocol.snapshotDocumentByteCount(
      operationJsonBytes: nextJsonBytes, operationCount: nextCount
    ) > maxTransferBytes {
      return .rejected("Session snapshot byte limit reached")
    }
    if case .profileUpdate(let update) = operation {
      let current = profiles[update.profile.peerKey]
      if current == nil || update.stamp > current!.0 {
        profiles[update.profile.peerKey] = (update.stamp, update.profile)
      }
    }
    state.operations[operation.id] = operation
    state.profiles = profiles.mapValues(\.1)
    state.highWaterMarks[operation.id.senderPeerKey] = max(
      state.highWaterMarks[operation.id.senderPeerKey] ?? 0,
      operation.id.senderSequence
    )
    state.latestStamp = max(state.latestStamp ?? operation.stamp, operation.stamp)
    operationJsonBytes = nextJsonBytes
    if case .erase = operation { eraseCount += 1 }
    WhiteboardProtocol.xorDigestInto(&digestXor, WhiteboardProtocol.operationStateDigest(operation))
    version += 1
    return .added
  }

  /// Atomically validates and merges a snapshot plus operations buffered behind it.
  /// Returns nil on success, or a rejection reason.
  @discardableResult
  public mutating func merge(_ operations: [BoardOperation]) -> String? {
    var uniqueIncoming: [OperationId: BoardOperation] = [:]
    for operation in operations {
      if let prior = uniqueIncoming[operation.id] {
        uniqueIncoming[operation.id] = canonicalOperation(prior, operation)
      } else {
        uniqueIncoming[operation.id] = operation
      }
    }

    var jsonByteDelta: Int64 = 0
    var additionalCount = 0
    var eraseCountDelta = 0
    var changed = false
    var digestXorDelta = Data(repeating: 0, count: sha256ByteCount)
    for operation in uniqueIncoming.values {
      if let existing = state.operations[operation.id] {
        let winner = canonicalOperation(existing, operation)
        if winner != existing {
          changed = true
          jsonByteDelta += Int64(WhiteboardProtocol.operationJsonByteCount(winner))
            - Int64(WhiteboardProtocol.operationJsonByteCount(existing))
          uniqueIncoming[operation.id] = winner
          if case .erase = winner { eraseCountDelta += 1 }
          if case .erase = existing { eraseCountDelta -= 1 }
          WhiteboardProtocol.xorDigestInto(
            &digestXorDelta, WhiteboardProtocol.operationStateDigest(existing)
          )
          WhiteboardProtocol.xorDigestInto(
            &digestXorDelta, WhiteboardProtocol.operationStateDigest(winner)
          )
        }
      } else {
        changed = true
        jsonByteDelta += Int64(WhiteboardProtocol.operationJsonByteCount(operation))
        additionalCount += 1
        if case .erase = operation { eraseCountDelta += 1 }
        WhiteboardProtocol.xorDigestInto(
          &digestXorDelta, WhiteboardProtocol.operationStateDigest(operation)
        )
      }
    }

    let nextCount = state.operations.count + additionalCount
    if nextCount > WhiteboardProtocol.maxSnapshotOperations {
      return "Merged snapshot exceeds the session operation limit"
    }
    if eraseCount + eraseCountDelta > WhiteboardProtocol.maxEraseOperations {
      return "Merged snapshot exceeds the session erase-operation limit"
    }
    let nextAuthors = Set(state.highWaterMarks.keys)
      .union(uniqueIncoming.keys.map(\.senderPeerKey))
      .count
    if nextAuthors > WhiteboardProtocol.maxStateVectorPeers {
      return "Merged snapshot exceeds the session author limit"
    }
    let nextJsonBytes = operationJsonBytes + jsonByteDelta
    if WhiteboardProtocol.snapshotDocumentByteCount(
      operationJsonBytes: nextJsonBytes, operationCount: nextCount
    ) > maxTransferBytes {
      return "Merged snapshot exceeds the session byte limit"
    }
    if !changed { return nil }

    var merged = state.operations
    for operation in uniqueIncoming.values {
      if let existing = state.operations[operation.id] {
        merged[operation.id] = canonicalOperation(existing, operation)
      } else {
        merged[operation.id] = operation
      }
    }
    state = rebuild(merged)
    operationJsonBytes = nextJsonBytes
    eraseCount += eraseCountDelta
    WhiteboardProtocol.xorDigestInto(&digestXor, digestXorDelta)
    version += 1
    return nil
  }

  public func material() -> KnownStateMaterial {
    KnownStateMaterial(
      state: state,
      version: version,
      digest: WhiteboardProtocol.stateDigestFromAccumulator(
        operationCount: state.operations.count,
        accumulator: digestXor
      )
    )
  }

  public func capture() -> KnownStateCapture {
    KnownStateCapture(state: state, version: version)
  }

  public func isCurrent(_ candidateVersion: Int64) -> Bool {
    version == candidateVersion
  }

  public func profile(_ peerKey: String) -> UserProfile? {
    state.profiles[peerKey]
  }

  private mutating func rebuild(
    _ operations: [OperationId: BoardOperation]
  ) -> BoardState {
    var highWaterMarks: [String: Int64] = [:]
    var latestStamp: OperationStamp? = nil
    profiles.removeAll()
    for operation in operations.values {
      highWaterMarks[operation.id.senderPeerKey] = max(
        highWaterMarks[operation.id.senderPeerKey] ?? 0,
        operation.id.senderSequence
      )
      latestStamp = max(latestStamp ?? operation.stamp, operation.stamp)
      if case .profileUpdate(let update) = operation {
        let current = profiles[update.profile.peerKey]
        if current == nil || update.stamp > current!.0 {
          profiles[update.profile.peerKey] = (update.stamp, update.profile)
        }
      }
    }
    return BoardState(
      profiles: profiles.mapValues(\.1),
      highWaterMarks: highWaterMarks,
      operations: operations,
      latestStamp: latestStamp
    )
  }
}
