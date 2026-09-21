public let maxSnapshotRetries = 3
public let snapshotRetryBaseDelayMillis: Int64 = 1_000
public let hydrationSlotPollMillis: Int64 = 250

public let transferInactivityTimeoutMillis: Int64 = 30_000
public let maxSnapshotTransferLifetimeMillis: Int64 = 5 * 60_000
public let snapshotFinishTimeoutMillis: Int64 = 3 * transferInactivityTimeoutMillis
public let maxHydrationSlotWaitMillis: Int64 =
  maxSnapshotTransferLifetimeMillis + snapshotFinishTimeoutMillis + transferInactivityTimeoutMillis

public struct HydrationSlotWait: Sendable {
  private let maxWaitPerHolderMillis: Int64
  private var holder: String? = nil
  private var waitedMillis: Int64 = 0

  public init(maxWaitPerHolderMillis: Int64 = maxHydrationSlotWaitMillis) {
    self.maxWaitPerHolderMillis = maxWaitPerHolderMillis
  }

  public var waitedOnCurrentHolderMillis: Int64 { waitedMillis }

  public mutating func keepWaiting(currentHolder: String, elapsedMillis: Int64) throws -> Bool {
    guard elapsedMillis >= 0 else {
      throw WhiteboardCoreError.requirementFailed("Elapsed time cannot be negative")
    }
    if currentHolder != holder {
      holder = currentHolder
      waitedMillis = 0
    } else {
      waitedMillis += elapsedMillis
    }
    return waitedMillis <= maxWaitPerHolderMillis
  }
}

public func snapshotRetryDelayMillis(attempt: Int) throws -> Int64 {
  guard attempt >= 1 else {
    throw WhiteboardCoreError.requirementFailed("Retry attempts are 1-based")
  }
  return snapshotRetryBaseDelayMillis &<< (Int64(attempt) - 1)
}

public func shouldScheduleSnapshotRetry(attempt: Int) -> Bool {
  attempt <= maxSnapshotRetries
}

public struct SnapshotRetryBudget: Sendable {
  private var attempts: [String: Int] = [:]

  public init() {}

  public mutating func consumeAttempt(_ peer: String) -> Int? {
    let attempt = (attempts[peer] ?? 0) + 1
    attempts[peer] = attempt
    return shouldScheduleSnapshotRetry(attempt: attempt) ? attempt : nil
  }

  public mutating func reset(_ peer: String) {
    attempts.removeValue(forKey: peer)
  }

  public mutating func retainPeers(_ peers: Set<String>) {
    attempts = attempts.filter { peers.contains($0.key) }
  }

  public mutating func clear() {
    attempts.removeAll()
  }
}

public enum SnapshotAckOutcome: Equatable, Sendable {
  case accepted
  case backpressure
  case failed
}

public func snapshotAckOutcome(accepted: Bool, busy: Bool) -> SnapshotAckOutcome {
  if accepted { return .accepted }
  if busy { return .backpressure }
  return .failed
}
