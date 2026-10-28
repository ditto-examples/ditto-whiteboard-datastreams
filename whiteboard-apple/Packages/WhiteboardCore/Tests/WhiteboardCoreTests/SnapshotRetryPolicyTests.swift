import Testing
@testable import WhiteboardCore

@Suite("SnapshotRetryPolicyTest")
struct SnapshotRetryPolicyTest {
  @Test func retryLadderBacksOffExponentiallyFromOneSecond() throws {
    #expect(try snapshotRetryDelayMillis(attempt: 1) == 1_000)
    #expect(try snapshotRetryDelayMillis(attempt: 2) == 2_000)
    #expect(try snapshotRetryDelayMillis(attempt: 3) == 4_000)
  }

  @Test func retryLadderRejectsNonPositiveAttempts() {
    #expect(throws: WhiteboardCoreError.self) {
      try snapshotRetryDelayMillis(attempt: 0)
    }
  }

  @Test func budgetAllowsExactlyTheConfiguredNumberOfRetriesThenGivesUp() {
    var budget = SnapshotRetryBudget()
    var granted: [Int] = []
    while let attempt = budget.consumeAttempt("peer") {
      granted.append(attempt)
    }
    #expect(granted == Array(1...maxSnapshotRetries))
  }

  @Test func budgetIsNotSpentByPeersThatNeverRetried() {
    var budget = SnapshotRetryBudget()
    for _ in 0..<maxSnapshotRetries {
      #expect(budget.consumeAttempt("noisy") != nil)
    }
    #expect(budget.consumeAttempt("noisy") == nil)

    #expect(budget.consumeAttempt("quiet") == 1)
  }

  @Test func resetRestoresTheFullBudgetForOnePeerOnly() {
    var budget = SnapshotRetryBudget()
    for _ in 0..<maxSnapshotRetries { _ = budget.consumeAttempt("a") }
    for _ in 0..<maxSnapshotRetries { _ = budget.consumeAttempt("b") }

    budget.reset("a")

    #expect(budget.consumeAttempt("a") == 1)
    #expect(budget.consumeAttempt("b") == nil)
  }

  @Test func retainPeersDropsDepartedPeersBudgets() {
    var budget = SnapshotRetryBudget()
    for _ in 0..<maxSnapshotRetries { _ = budget.consumeAttempt("gone") }
    for _ in 0..<maxSnapshotRetries { _ = budget.consumeAttempt("present") }

    budget.retainPeers(["present"])

    #expect(budget.consumeAttempt("gone") == 1)
    #expect(budget.consumeAttempt("present") == nil)
  }

  @Test func waitingSurvivesTheSlotBeingHandedBetweenEveryPeerInTurn() throws {
    var wait = HydrationSlotWait()
    var keptWaiting = true

    for holder in 0..<(maxConnectedPeers - 1) {
      var elapsed: Int64 = 0
      while elapsed < maxSnapshotTransferLifetimeMillis {
        keptWaiting = try keptWaiting
          && wait.keepWaiting(
            currentHolder: "peer-\(holder)/transfer-\(holder)",
            elapsedMillis: hydrationSlotPollMillis
          )
        elapsed += hydrationSlotPollMillis
      }
    }

    #expect(keptWaiting)
  }

  @Test func aSingleHolderThatOverstaysOneTransferLifetimeEndsTheWait() throws {
    var wait = HydrationSlotWait()
    var elapsed: Int64 = 0
    while try wait.keepWaiting(
      currentHolder: "stuck-peer/stuck-transfer", elapsedMillis: hydrationSlotPollMillis
    ) {
      elapsed += hydrationSlotPollMillis
      #expect(elapsed <= maxHydrationSlotWaitMillis * 2)
    }

    #expect(elapsed >= maxSnapshotTransferLifetimeMillis)
  }

  @Test func aHolderChangeRestartsTheBudgetEvenForTheSamePeerRetransmitting() throws {
    var wait = HydrationSlotWait()
    for _ in 0..<10 {
      _ = try wait.keepWaiting(
        currentHolder: "peer-a/transfer-1", elapsedMillis: hydrationSlotPollMillis
      )
    }
    #expect(wait.waitedOnCurrentHolderMillis > 0)

    _ = try wait.keepWaiting(
      currentHolder: "peer-a/transfer-2", elapsedMillis: hydrationSlotPollMillis
    )

    #expect(wait.waitedOnCurrentHolderMillis == 0)
  }

  @Test func slotWaitRejectsNegativeElapsedTime() {
    var wait = HydrationSlotWait()
    #expect(throws: WhiteboardCoreError.self) {
      try wait.keepWaiting(currentHolder: "peer/transfer", elapsedMillis: -1)
    }
  }

  @Test func aBusyRefusalIsBackpressureAndNeverConsumesTheErrorBudget() {
    #expect(snapshotAckOutcome(accepted: false, busy: true) == .backpressure)
  }

  @Test func aPlainRefusalIsStillAFailure() {
    #expect(snapshotAckOutcome(accepted: false, busy: false) == .failed)
  }

  @Test func anAcceptedAckIsAcceptedRegardlessOfTheBusyFlag() {
    #expect(snapshotAckOutcome(accepted: true, busy: false) == .accepted)
    #expect(snapshotAckOutcome(accepted: true, busy: true) == .accepted)
  }

  @Test func onlyBackpressureBypassesTheRetryLadder() {
    var budget = SnapshotRetryBudget()
    for _ in 0..<3 {
      let outcome = snapshotAckOutcome(accepted: false, busy: false)
      #expect(outcome == .failed)
      #expect(budget.consumeAttempt("peer") != nil)
    }
    #expect(budget.consumeAttempt("peer") == nil)
  }
}
