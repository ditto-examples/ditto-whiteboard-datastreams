import Foundation

public enum BoardReducer {
  public static func apply(_ state: BoardState, operation: BoardOperation) -> BoardState {
    if let existing = state.operations[operation.id] {
      let winner = canonicalOperation(existing, operation)
      if winner == existing { return state }
      var operations = state.operations
      operations[operation.id] = winner
      return rebuild(operations)
    }
    let priorMax = state.latestStamp
    if priorMax == nil || operation.stamp > priorMax! {
      return applyNewest(state, operation)
    } else if case .commit(let commit) = operation {
      if let applied = applyOutOfOrderCommit(state, operation: commit) {
        return applied
      }
      var operations = state.operations
      operations[operation.id] = operation
      return rebuild(operations)
    } else {
      var operations = state.operations
      operations[operation.id] = operation
      return rebuild(operations)
    }
  }

  public static func merge(_ state: BoardState, operations: some Sequence<BoardOperation>) -> BoardState {
    var merged = state.operations
    for operation in operations {
      if let existing = merged[operation.id] {
        merged[operation.id] = canonicalOperation(existing, operation)
      } else {
        merged[operation.id] = operation
      }
    }
    return rebuild(merged)
  }

  private static func applyNewest(_ state: BoardState, _ operation: BoardOperation) -> BoardState {
    var next = state
    next.operations[operation.id] = operation
    next.highWaterMarks[operation.id.senderPeerKey] = max(
      state.highWaterMarks[operation.id.senderPeerKey] ?? 0,
      operation.id.senderSequence
    )
    next.latestStamp = operation.stamp
    switch operation {
    case .profileUpdate(let update):
      next.profiles[update.profile.peerKey] = update.profile
    case .clear:
      next.objects = [:]
      next.erasures = []
      next.clearWatermark = operation.stamp
      next.renderCapacityReachedSinceClear = false
    case .commit(let commit):
      if state.objects.count < maxRenderedBoardObjects {
        next.objects[commit.boardObject.id] = commit.boardObject
      }
      next.renderCapacityReachedSinceClear =
        state.renderCapacityReachedSinceClear || next.objects.count >= maxRenderedBoardObjects
    case .erase(let erase):
      next.objects = Dictionary(
        BoardGeometry.erase(state.objects.values, eraser: erase).map { ($0.id, $0) },
        uniquingKeysWith: { first, _ in first }
      )
      next.erasures.append(erase)
      next.renderCapacityReachedSinceClear =
        state.renderCapacityReachedSinceClear || next.objects.count >= maxRenderedBoardObjects
    }
    return next
  }

  private static func applyOutOfOrderCommit(
    _ state: BoardState, operation: BoardOperation.Commit
  ) -> BoardState? {
    var next = state
    next.operations[operation.id] = .commit(operation)
    next.highWaterMarks[operation.id.senderPeerKey] = max(
      state.highWaterMarks[operation.id.senderPeerKey] ?? 0,
      operation.id.senderSequence
    )
    if let watermark = state.clearWatermark, operation.stamp <= watermark {
      return next
    }
    if state.renderCapacityReachedSinceClear
      || state.erasures.contains(where: { $0.stamp > operation.stamp })
      || state.objects.count >= maxRenderedBoardObjects
    {
      return nil
    }
    next.objects[operation.boardObject.id] = operation.boardObject
    next.renderCapacityReachedSinceClear = next.objects.count >= maxRenderedBoardObjects
    return next
  }

  public static func rebuild(_ operations: [OperationId: BoardOperation]) -> BoardState {
    var objects: [ObjectId: BoardObject] = [:]
    var erasures: [BoardOperation.Erase] = []
    var clearWatermark: OperationStamp? = nil
    var profiles: [String: (OperationStamp, UserProfile)] = [:]
    var highWaterMarks: [String: Int64] = [:]
    var renderCapacityReachedSinceClear = false

    let ordered = operations.values.sorted { first, second in
      if first.stamp != second.stamp { return first.stamp < second.stamp }
      if first.id.senderPeerKey != second.id.senderPeerKey {
        return first.id.senderPeerKey < second.id.senderPeerKey
      }
      return first.id.senderSequence < second.id.senderSequence
    }
    for operation in ordered {
      highWaterMarks[operation.id.senderPeerKey] = max(
        highWaterMarks[operation.id.senderPeerKey] ?? 0,
        operation.id.senderSequence
      )
      switch operation {
      case .profileUpdate(let update):
        let current = profiles[update.profile.peerKey]
        if current == nil || update.stamp > current!.0 {
          profiles[update.profile.peerKey] = (update.stamp, update.profile)
        }
      case .clear:
        if clearWatermark == nil || operation.stamp > clearWatermark! {
          clearWatermark = operation.stamp
          objects.removeAll()
          erasures.removeAll()
          renderCapacityReachedSinceClear = false
        }
      case .commit(let commit):
        if (clearWatermark == nil || operation.stamp > clearWatermark!)
          && objects.count < maxRenderedBoardObjects
        {
          objects[commit.boardObject.id] = commit.boardObject
        }
        if objects.count >= maxRenderedBoardObjects {
          renderCapacityReachedSinceClear = true
        }
      case .erase(let erase):
        if clearWatermark == nil || operation.stamp > clearWatermark! {
          let survivors = BoardGeometry.erase(objects.values, eraser: erase)
          objects = Dictionary(survivors.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
          erasures.append(erase)
          if objects.count >= maxRenderedBoardObjects {
            renderCapacityReachedSinceClear = true
          }
        }
      }
    }

    return BoardState(
      objects: objects,
      erasures: erasures,
      clearWatermark: clearWatermark,
      profiles: profiles.mapValues(\.1),
      highWaterMarks: highWaterMarks,
      operations: operations,
      latestStamp: operations.values.map(\.stamp).max(),
      renderCapacityReachedSinceClear: renderCapacityReachedSinceClear
    )
  }
}

public struct OperationClock: Sendable {
  public let peerKey: String
  private var senderSequence: Int64
  private var lamport: Int64

  public init(peerKey: String, initialSenderSequence: Int64 = 0, initialLamport: Int64? = nil) {
    self.peerKey = peerKey
    self.senderSequence = initialSenderSequence
    self.lamport = initialLamport ?? initialSenderSequence
  }

  public var currentSenderSequence: Int64 { senderSequence }
  public var currentLamport: Int64 { lamport }

  public mutating func next() throws -> (OperationId, OperationStamp) {
    guard senderSequence < maxOperationCounter && lamport < maxOperationCounter else {
      throw WhiteboardCoreError.requirementFailed("Operation clock space is exhausted")
    }
    senderSequence += 1
    lamport += 1
    return (
      OperationId(senderPeerKey: peerKey, senderSequence: senderSequence),
      OperationStamp(lamport: lamport, peerKey: peerKey, senderSequence: senderSequence)
    )
  }

  public mutating func observe(_ stamp: OperationStamp) {
    lamport = max(lamport, stamp.lamport)
  }
}
