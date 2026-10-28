import Foundation
import Testing
import WhiteboardCore

@testable import WhiteboardKit
import DittoSwift

/// Pins the DittoPresenceGraph → PresenceGraphSnapshot mapping (WhiteboardCore stays
/// Ditto-free, so this is the only place the bridging is verified) and the
/// `system:data_sync_info` row parsing.
@Suite("Presence snapshot mapping")
struct PresenceSnapshotMappingTests {
  private func makePeer() -> DittoPeer {
    DittoPeer(
      peerKey: "peer-key-1",
      connections: [
        DittoConnection(id: "c1", type: .bluetooth, peer1: "local", peer2: "peer-key-1"),
        DittoConnection(id: "c2", type: .multicast, peer1: "peer-key-1", peer2: "peer-key-2"),
      ],
      deviceName: "Pixel Tablet",
      isConnectedToDittoServer: true,
      os: nil,
      dittoSDKVersion: "5.2.0-dev",
      isCompatible: true,
      peerMetadata: ["application": "ditto-whiteboard", "missing": nil],
      identityServiceMetadata: [:]
    )
  }

  @Test("Peer fields, connections, and metadata map through")
  func peerMapping() {
    let snapshot = presencePeerSnapshot(makePeer())

    #expect(snapshot.peerKey == "peer-key-1")
    #expect(snapshot.deviceName == "Pixel Tablet")
    #expect(snapshot.isConnectedToDittoServer)
    #expect(snapshot.dittoSDKVersion == "5.2.0-dev")
    #expect(snapshot.isCompatible == true)
    #expect(snapshot.connections.count == 2)
    #expect(snapshot.connections[0].type == .bluetooth)
    #expect(snapshot.connections[0].peer1 == "local")
    #expect(snapshot.connections[1].type == .multicast)
    // Nil metadata values are dropped; empty maps encode to nil.
    #expect(snapshot.peerMetadataKeyCount == 1)
    #expect(snapshot.peerMetadataJSON?.contains("ditto-whiteboard") == true)
    #expect(snapshot.identityMetadataJSON == nil)
    #expect(snapshot.identityMetadataKeyCount == 0)
  }

  @Test("An empty SDK version maps to nil")
  func emptySDKVersion() {
    let peer = DittoPeer(
      peerKey: "k",
      connections: [],
      deviceName: "",
      isConnectedToDittoServer: false,
      dittoSDKVersion: ""
    )
    #expect(presencePeerSnapshot(peer).dittoSDKVersion == nil)
  }

  @Test("Every DittoConnectionType maps to a known snapshot type")
  func connectionTypeMapping() {
    #expect(presenceConnectionType(.bluetooth) == .bluetooth)
    #expect(presenceConnectionType(.accessPoint) == .accessPoint)
    #expect(presenceConnectionType(.p2pWiFi) == .p2pWiFi)
    #expect(presenceConnectionType(.webSocket) == .webSocket)
    #expect(presenceConnectionType(.multicast) == .multicast)
  }

  @Test("Graph snapshot mirrors localPeer plus remotePeers")
  func graphMapping() {
    let remote = makePeer()
    let graph = DittoPresenceGraph(
      localPeer: DittoPeer(peerKey: "local", connections: [], deviceName: "This Mac", isConnectedToDittoServer: false),
      remotePeers: [remote]
    )
    let snapshot = presenceGraphSnapshot(graph)
    #expect(snapshot.localPeer.peerKey == "local")
    #expect(snapshot.remotePeers.map(\.peerKey) == ["peer-key-1"])
  }

  @Test("Sync rows parse flat fields, nested documents, and skip rows without _id")
  func syncStatusParsing() {
    let items: [[String: Any?]] = [
      ["_id": "flat", "synced_up_to_local_commit_id": Int64(12), "last_update_received_time": 1_700_000_000_000.0],
      ["_id": "nested", "documents": ["synced_up_to_local_commit_id": 7, "last_update_received_time": 42.0]],
      ["synced_up_to_local_commit_id": 99], // no _id → skipped
      ["_id": "empty"],
    ]
    let parsed = parseSyncStatusItems(items)
    #expect(parsed.count == 3)
    #expect(parsed["flat"] == PeerSyncStatus(syncedUpToLocalCommitId: 12, lastUpdateReceivedTime: 1_700_000_000_000.0))
    #expect(parsed["nested"] == PeerSyncStatus(syncedUpToLocalCommitId: 7, lastUpdateReceivedTime: 42.0))
    #expect(parsed["empty"] == PeerSyncStatus())
  }
}
