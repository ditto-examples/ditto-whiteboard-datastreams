import Foundation
import WhiteboardCore
@_spi(PreviewDataStreams) import DittoSwift

/// Presence-graph observation and sync-status reads for the presence viewer. Unlike the
/// session's stream topology — which filters presence to admitted whiteboard peers — this
/// exposes the raw, unfiltered graph (every peer Ditto knows about) so the viewer shows
/// the full mesh like Edge Studio.
extension DittoWhiteboardTransport {
  private static let syncStatusQueryTimeoutMillis: Int64 = 2_000

  public func observePresenceGraph(
    _ handler: @escaping @Sendable (PresenceGraphSnapshot) -> Void
  ) -> PresenceGraphObservationToken {
    let observer = ditto.presence.observe { graph in
      handler(presenceGraphSnapshot(graph))
    }
    return PresenceGraphObservationToken { observer.stop() }
  }

  /// Reads `system:data_sync_info` from the local store with a 2s deadline, falling back
  /// to the last successful read. The SmallPeersOnly offline identity still answers this
  /// query against the local store.
  public func syncStatusByPeerKey() async -> [String: PeerSyncStatus] {
    do {
      if let result = try await withTimeout(milliseconds: Self.syncStatusQueryTimeoutMillis, { [ditto] in
        try await ditto.store.execute(query: "SELECT * FROM system:data_sync_info")
      }) {
        let parsed = parseSyncStatusItems(result.items.map(\.value))
        withState { $0.lastSyncStatusByPeerKey = parsed }
        return parsed
      }
    } catch {
      #if DEBUG
        FileHandle.standardError.write(
          "DittoWhiteboard: system:data_sync_info unavailable — \(error.localizedDescription)\n"
            .data(using: .utf8) ?? Data()
        )
      #endif
    }
    return withState { $0.lastSyncStatusByPeerKey }
  }
}

/// DittoPresenceGraph → the Ditto-free snapshot the viewer consumes. Internal (not
/// private) so WhiteboardKitTests can pin the mapping without a live Ditto instance.
func presenceGraphSnapshot(_ graph: DittoPresenceGraph) -> PresenceGraphSnapshot {
  PresenceGraphSnapshot(
    localPeer: presencePeerSnapshot(graph.localPeer),
    remotePeers: graph.remotePeers.map(presencePeerSnapshot)
  )
}

func presencePeerSnapshot(_ peer: DittoPeer) -> PresencePeerSnapshot {
  let peerMetadata = encodePresenceMetadata(peer.peerMetadata)
  let identityMetadata = encodePresenceMetadata(peer.identityServiceMetadata)
  return PresencePeerSnapshot(
    peerKey: peer.peerKey,
    deviceName: peer.deviceName,
    connections: peer.connections.map {
      PresenceConnectionSnapshot(
        id: $0.id,
        type: presenceConnectionType($0.type),
        peer1: $0.peer1,
        peer2: $0.peer2
      )
    },
    isConnectedToDittoServer: peer.isConnectedToDittoServer,
    osName: peer.os.map { "\($0)" },
    dittoSDKVersion: peer.dittoSDKVersion?.isEmpty == false ? peer.dittoSDKVersion : nil,
    isCompatible: peer.isCompatible,
    peerMetadataJSON: peerMetadata?.json,
    peerMetadataKeyCount: peerMetadata?.keyCount ?? 0,
    identityMetadataJSON: identityMetadata?.json,
    identityMetadataKeyCount: identityMetadata?.keyCount ?? 0
  )
}

func presenceConnectionType(_ type: DittoConnectionType) -> PresenceConnectionType {
  switch type {
  case .bluetooth: return .bluetooth
  case .accessPoint: return .accessPoint
  case .p2pWiFi: return .p2pWiFi
  case .webSocket: return .webSocket
  case .multicast: return .multicast
  @unknown default: return .unknown("\(type)")
  }
}

/// JSON-encodes a metadata dictionary (nil values dropped); nil when empty or unencodable.
func encodePresenceMetadata(_ metadata: [String: Any?]) -> (json: String, keyCount: Int)? {
  let filtered = metadata.compactMapValues { $0 }
  guard !filtered.isEmpty,
    JSONSerialization.isValidJSONObject(filtered),
    let data = try? JSONSerialization.data(
      withJSONObject: filtered,
      options: [.prettyPrinted, .sortedKeys]
    ),
    let json = String(data: data, encoding: .utf8)
  else {
    return nil
  }
  return (json, filtered.count)
}

/// Parses `system:data_sync_info` rows. Fields are read flat first (per the SDK docs)
/// and from the nested `documents` object as a fallback, matching Edge Studio.
func parseSyncStatusItems(_ items: [[String: Any?]]) -> [String: PeerSyncStatus] {
  var parsed: [String: PeerSyncStatus] = [:]
  for item in items {
    let dict = item.compactMapValues { $0 }
    guard let peerKey = dict["_id"] as? String else { continue }
    let documents = dict["documents"] as? [String: Any]
    let commit = (dict["synced_up_to_local_commit_id"] as? NSNumber)
      ?? (documents?["synced_up_to_local_commit_id"] as? NSNumber)
    let lastUpdate = (dict["last_update_received_time"] as? NSNumber)
      ?? (documents?["last_update_received_time"] as? NSNumber)
    parsed[peerKey] = PeerSyncStatus(
      syncedUpToLocalCommitId: commit?.int64Value,
      lastUpdateReceivedTime: lastUpdate?.doubleValue
    )
  }
  return parsed
}
