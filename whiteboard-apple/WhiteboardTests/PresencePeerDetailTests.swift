import Foundation
import Testing

import WhiteboardCore

@testable import Whiteboard

/// Unit tests for `PresencePeerDetail` — the payload behind the presence viewer's
/// focus-mode detail card.
///
/// The property that matters is the three-way sync split. `system:data_sync_info` is a
/// local table computed from where this device actually receives data, so it has rows
/// only for peers we hold a session with — never for an indirect peer, and never for
/// ourselves. The card has to tell those apart rather than rendering a blank, and these
/// pin that down.
@Suite("PresencePeerDetail")
struct PresencePeerDetailTests {
  private func mockPeer(
    key: String = "p1",
    name: String = "Pixel 10a",
    cloud: Bool = false
  ) -> MockPeer {
    MockPeer(peerKey: key, deviceName: name, connections: [], isConnectedToDittoCloud: cloud)
  }

  private func syncStatus(commit: Int64?, lastUpdate: TimeInterval?) -> PeerSyncStatus {
    PeerSyncStatus(syncedUpToLocalCommitId: commit, lastUpdateReceivedTime: lastUpdate)
  }

  // MARK: Sync three-way split

  @Test("A directly connected peer carries its commit id and last update")
  func directPeerCarriesSyncProgress() {
    let detail = PresencePeerDetail(
      peer: mockPeer(),
      isLocal: false,
      isDirectlyConnected: true,
      syncStatus: syncStatus(commit: 42, lastUpdate: 1_700_000_000_000)
    )

    #expect(detail.isDirectlyConnected)
    #expect(detail.syncedUpToLocalCommitId == 42)
    #expect(detail.lastUpdateReceivedTime == 1_700_000_000_000)
  }

  @Test("An indirect peer reports no sync session at all")
  func indirectPeerHasNoSyncSession() {
    // No row exists for it, so the card must say "no sync session" rather than
    // rendering an empty commit id that reads as a bug.
    let detail = PresencePeerDetail(
      peer: mockPeer(),
      isLocal: false,
      isDirectlyConnected: false,
      syncStatus: nil
    )

    #expect(!detail.isDirectlyConnected)
    #expect(detail.syncedUpToLocalCommitId == nil)
    #expect(detail.lastUpdateReceivedTime == nil)
  }

  @Test("The local peer is never 'directly connected' and never has sync rows")
  func localPeerIsItsOwnCase() {
    // data_sync_info records what REMOTE peers confirmed of OUR commits, so there is
    // no row for ourselves. Even handed one, the local card must not claim progress.
    let detail = PresencePeerDetail(
      peer: mockPeer(key: "local", name: "This Mac"),
      isLocal: true,
      isDirectlyConnected: true,
      syncStatus: syncStatus(commit: 99, lastUpdate: 1_700_000_000_000)
    )

    #expect(detail.isLocal)
    #expect(!detail.isDirectlyConnected, "a session with oneself does not exist")
    #expect(detail.syncedUpToLocalCommitId == nil)
    #expect(detail.lastUpdateReceivedTime == nil)
    #expect(detail.displayName == "Me")
  }

  // MARK: Presence-graph facts survive for indirect peers

  @Test("Presence-graph facts are populated regardless of reachability")
  func presenceFactsPopulatedForIndirectPeers() {
    // The whole reason the card is worth opening on a peer we cannot reach.
    let detail = PresencePeerDetail(
      peer: mockPeer(cloud: true),
      isLocal: false,
      isDirectlyConnected: false,
      syncStatus: nil
    )

    #expect(detail.peerKey == "p1")
    #expect(detail.displayName == "Pixel 10a")
    #expect(detail.isConnectedToDittoCloud, "a peer we can't reach may still have a cloud link")
  }

  @Test("A blank device name falls back to a truncated peer key")
  func blankDeviceNameFallsBack() {
    let detail = PresencePeerDetail(
      peer: mockPeer(key: "abcdefghijklmnop", name: ""),
      isLocal: false,
      isDirectlyConnected: false,
      syncStatus: nil
    )

    #expect(detail.displayName == "abcdefgh")
  }

  @Test("Snapshot detail fields (OS, SDK version, metadata) reach the card")
  func snapshotDetailFieldsReachCard() {
    // PresencePeerSnapshot carries the detail-card fields the protocol's mock
    // defaults omit — pin the bridging used in production.
    let snapshot = PresencePeerSnapshot(
      peerKey: "p1",
      deviceName: "Studio Mac",
      isConnectedToDittoServer: true,
      osName: "macOS",
      dittoSDKVersion: "5.2.0",
      isCompatible: true,
      peerMetadataJSON: "{\n  \"application\" : \"ditto-whiteboard\"\n}",
      peerMetadataKeyCount: 1
    )

    let detail = PresencePeerDetail(
      peer: snapshot,
      isLocal: false,
      isDirectlyConnected: true,
      syncStatus: nil
    )

    #expect(detail.os == .macOS)
    #expect(detail.sdkVersion == "5.2.0")
    #expect(detail.isCompatible == true)
    #expect(detail.peerMetadataKeyCount == 1)
    #expect(detail.peerMetadataJSON != nil)
    #expect(detail.identityMetadataJSON == nil)
  }

  @Test("Metadata key counts default to zero when the peer reports none")
  func metadataDefaultsToNone() {
    let detail = PresencePeerDetail(
      peer: mockPeer(),
      isLocal: false,
      isDirectlyConnected: true,
      syncStatus: nil
    )

    #expect(detail.peerMetadataKeyCount == 0)
    #expect(detail.peerMetadataJSON == nil)
    #expect(detail.identityMetadataKeyCount == 0)
  }

  // MARK: PeerOS mapping

  @Test("PeerOS(osName:) is nil for an unknown peer OS")
  func peerOSMappingHandlesNil() {
    #expect(PeerOS(osName: nil) == nil)
  }

  @Test("PeerOS(osName:) maps the SDK's OS descriptions")
  func peerOSMappingMatchesDescriptions() {
    #expect(PeerOS(osName: "iOS") == .iOS)
    #expect(PeerOS(osName: "android") == .android)
    #expect(PeerOS(osName: "macOS") == .macOS)
    #expect(PeerOS(osName: "Linux") == .linux)
    #expect(PeerOS(osName: "Windows") == .windows)
    #expect(PeerOS(osName: "Plan 9") == .unknown(name: "Plan 9"))
  }
}
