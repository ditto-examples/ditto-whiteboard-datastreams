import Foundation
import Testing

import WhiteboardCore

@testable import Whiteboard

/// ViewModel integration through the injected presence seam: a fake source pushes
/// `PresenceGraphSnapshot`s (no Ditto instance), and the 250 ms throttled flush must
/// rebuild search candidates and refresh sync rows — the same pipeline production
/// drives from `WhiteboardTransport.observePresenceGraph`.
@Suite("PresenceViewerScreen.ViewModel presence source")
@MainActor
final class PresenceViewerViewModelTests {
  private final class FakePresenceSource: @unchecked Sendable {
    private(set) var handler: (@Sendable (PresenceGraphSnapshot) -> Void)?
    private(set) var cancelled = false

    func register(_ handler: @escaping @Sendable (PresenceGraphSnapshot) -> Void)
      -> PresenceGraphObservationToken
    {
      self.handler = handler
      return PresenceGraphObservationToken { [weak self] in self?.cancelled = true }
    }

    func push(_ snapshot: PresenceGraphSnapshot) {
      handler?(snapshot)
    }
  }

  private func twoPeerSnapshot() -> PresenceGraphSnapshot {
    PresenceGraphSnapshot(
      localPeer: PresencePeerSnapshot(
        peerKey: "local",
        deviceName: "Local",
        connections: [
          PresenceConnectionSnapshot(id: "l-A", type: .p2pWiFi, peer1: "local", peer2: "A")
        ]
      ),
      remotePeers: [
        PresencePeerSnapshot(peerKey: "A", deviceName: "Alpha", isConnectedToDittoServer: false)
      ]
    )
  }

  private func waitFor(
    _ timeout: Duration = .seconds(3),
    _ condition: @MainActor () -> Bool
  ) async -> Bool {
    let clock = ContinuousClock()
    let deadline = clock.now + timeout
    while clock.now < deadline {
      if condition() { return true }
      try? await Task.sleep(for: .milliseconds(10))
    }
    return condition()
  }

  @Test("An injected snapshot drives search candidates through the throttle window")
  func injectedSnapshotDrivesSearch() async {
    let source = FakePresenceSource()
    let viewModel = PresenceViewerScreen.ViewModel(
      presenceRegistration: { source.register($0) },
      syncStatusFetcher: { [:] }
    )

    await viewModel.startProductionMode()
    #expect(source.handler != nil, "registration must reach the injected source")

    viewModel.searchQuery = "alpha"
    source.push(twoPeerSnapshot())

    #expect(await waitFor { viewModel.searchMatches.map(\.key) == ["A"] })
  }

  @Test("Sync rows from the injected fetcher land on the open detail card")
  func syncRowsReachDetailCard() async {
    let source = FakePresenceSource()
    let viewModel = PresenceViewerScreen.ViewModel(
      presenceRegistration: { source.register($0) },
      syncStatusFetcher: { ["A": PeerSyncStatus(syncedUpToLocalCommitId: 7, lastUpdateReceivedTime: nil)] }
    )

    await viewModel.startProductionMode()
    source.push(twoPeerSnapshot())
    #expect(await waitFor { viewModel.syncStatusByPeerKey["A"]?.syncedUpToLocalCommitId == 7 })

    viewModel.detailPeerKey = "A"
    let detail = viewModel.openPeerDetail
    #expect(detail?.displayName == "Alpha")
    #expect(detail?.isDirectlyConnected == true)
    #expect(detail?.syncedUpToLocalCommitId == 7)
  }

  @Test("stopProductionMode cancels the observation token")
  func stopCancelsToken() async {
    let source = FakePresenceSource()
    let viewModel = PresenceViewerScreen.ViewModel(
      presenceRegistration: { source.register($0) }
    )

    await viewModel.startProductionMode()
    viewModel.stopProductionMode()

    #expect(source.cancelled)
  }

  @Test("startProductionMode without a source is a no-op")
  func noSourceIsNoOp() async {
    let viewModel = PresenceViewerScreen.ViewModel()
    await viewModel.startProductionMode()
    #expect(viewModel.searchMatches.isEmpty)
    #expect(viewModel.openPeerDetail == nil)
  }
}
