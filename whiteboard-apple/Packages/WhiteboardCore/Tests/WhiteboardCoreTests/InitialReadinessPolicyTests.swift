import Testing
@testable import WhiteboardCore

@Suite("InitialReadinessPolicyTest")
struct InitialReadinessPolicyTest {
  @Test func digestMismatchAfterTimeoutWaiverDoesNotRelockEditing() {
    #expect(
      !shouldGatePeerForInitialSync(peer: "peer", synchronizedPeers: [], waivedPeers: ["peer"])
    )
  }

  @Test func onlyAnUnresolvedUnwaivedPeerGatesEditing() {
    #expect(shouldGatePeerForInitialSync(peer: "peer", synchronizedPeers: [], waivedPeers: []))
    #expect(!shouldGatePeerForInitialSync(peer: "peer", synchronizedPeers: ["peer"], waivedPeers: []))
  }

  @Test func equalDigestCompletedBeforeReadinessDelayIsNotRegated() {
    var awaiting: Set<String> = []
    #expect(
      !tryAwaitInitialSync(
        peer: "peer",
        synchronizedPeers: { ["peer"] },
        waivedPeers: { [] },
        awaitingPeers: &awaiting
      )
    )
    #expect(awaiting.isEmpty)
  }

  @Test func completionBetweenCandidateCheckAndAwaitingInsertCannotRegatePeer() {
    var synchronized: Set<String> = []
    var awaiting: Set<String> = []

    #expect(
      !tryAwaitInitialSync(
        peer: "peer",
        synchronizedPeers: { synchronized },
        waivedPeers: { [] },
        awaitingPeers: &awaiting,
        afterInitialCheck: { synchronized.insert("peer") }
      )
    )
    #expect(awaiting.isEmpty)
  }

  @Test func activeAbsoluteDeadlineIsNotRestartedByMoreTrafficOrPeers() {
    #expect(
      shouldStartInitialSyncDeadline(
        ready: true, initialReadinessResolved: false, timeoutActive: false
      )
    )
    #expect(
      !shouldStartInitialSyncDeadline(
        ready: true, initialReadinessResolved: false, timeoutActive: true
      )
    )
  }

  @Test func timeoutWarningCanClearAfterWaivedPeerBecomesIncompatible() {
    #expect(
      hasOutstandingInitialSync(visiblePeers: ["peer"], awaitingPeers: [], waivedPeers: ["peer"])
    )
    #expect(!hasOutstandingInitialSync(visiblePeers: ["peer"], awaitingPeers: [], waivedPeers: []))
  }

  @Test func newPeerGateClearsPriorWaiverMessageEvenWhileOldPeerRemainsVisible() {
    let timedOut = "Initial nearby sync timed out. Editing is available."

    #expect(
      connectivityMessageAfterNewInitialSyncGate(currentMessage: timedOut, newGateStarted: true)
        == nil
    )
    #expect(
      connectivityMessageAfterNewInitialSyncGate(currentMessage: timedOut, newGateStarted: false)
        == timedOut
    )
    #expect(
      connectivityMessageAfterNewInitialSyncGate(
        currentMessage: "Nearby presence failed", newGateStarted: true
      ) == "Nearby presence failed"
    )
  }
}
