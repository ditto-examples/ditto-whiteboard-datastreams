import Foundation

/// Ditto-free mirror of the SDK's connection types, so the presence viewer and its tests
/// never import the (SPI-gated) DittoSwift module.
public enum PresenceConnectionType: Equatable, Sendable, Hashable {
  case bluetooth
  case accessPoint
  case p2pWiFi
  case webSocket
  case multicast
  case unknown(String)
}

/// One peer-to-peer connection as reported by the presence graph.
public struct PresenceConnectionSnapshot: Equatable, Sendable {
  public var id: String
  public var type: PresenceConnectionType
  public var peer1: String
  public var peer2: String

  public init(id: String, type: PresenceConnectionType, peer1: String, peer2: String) {
    self.id = id
    self.type = type
    self.peer1 = peer1
    self.peer2 = peer2
  }
}

/// One peer in the presence graph. Metadata is pre-encoded to JSON plus its top-level key
/// count so the snapshot stays `Sendable` and `Equatable` (the raw `[String: Any?]`
/// dictionaries are neither).
public struct PresencePeerSnapshot: Equatable, Sendable {
  public var peerKey: String
  public var deviceName: String
  public var connections: [PresenceConnectionSnapshot]
  public var isConnectedToDittoServer: Bool
  /// Raw OS name as reported by the SDK (`DittoPeerOS` description), if known yet.
  public var osName: String?
  public var dittoSDKVersion: String?
  public var isCompatible: Bool?
  public var peerMetadataJSON: String?
  public var peerMetadataKeyCount: Int
  public var identityMetadataJSON: String?
  public var identityMetadataKeyCount: Int

  public init(
    peerKey: String,
    deviceName: String = "",
    connections: [PresenceConnectionSnapshot] = [],
    isConnectedToDittoServer: Bool = false,
    osName: String? = nil,
    dittoSDKVersion: String? = nil,
    isCompatible: Bool? = nil,
    peerMetadataJSON: String? = nil,
    peerMetadataKeyCount: Int = 0,
    identityMetadataJSON: String? = nil,
    identityMetadataKeyCount: Int = 0
  ) {
    self.peerKey = peerKey
    self.deviceName = deviceName
    self.connections = connections
    self.isConnectedToDittoServer = isConnectedToDittoServer
    self.osName = osName
    self.dittoSDKVersion = dittoSDKVersion
    self.isCompatible = isCompatible
    self.peerMetadataJSON = peerMetadataJSON
    self.peerMetadataKeyCount = peerMetadataKeyCount
    self.identityMetadataJSON = identityMetadataJSON
    self.identityMetadataKeyCount = identityMetadataKeyCount
  }
}

/// The raw presence graph — every known peer, not just admitted whiteboard peers. The
/// whiteboard session's stream topology deliberately filters presence to same-board peers;
/// the presence viewer (like Edge Studio) shows the full mesh, so it observes this
/// unfiltered snapshot instead.
public struct PresenceGraphSnapshot: Equatable, Sendable {
  public var localPeer: PresencePeerSnapshot
  public var remotePeers: [PresencePeerSnapshot]

  public init(localPeer: PresencePeerSnapshot, remotePeers: [PresencePeerSnapshot]) {
    self.localPeer = localPeer
    self.remotePeers = remotePeers
  }
}

/// One row of `system:data_sync_info` reduced to what the peer detail card draws. The
/// table records what remote peers have confirmed of our commits, so rows exist only for
/// directly connected peers — never for indirect peers or for ourselves.
public struct PeerSyncStatus: Equatable, Sendable {
  public var syncedUpToLocalCommitId: Int64?
  public var lastUpdateReceivedTime: Double?

  public init(syncedUpToLocalCommitId: Int64? = nil, lastUpdateReceivedTime: Double? = nil) {
    self.syncedUpToLocalCommitId = syncedUpToLocalCommitId
    self.lastUpdateReceivedTime = lastUpdateReceivedTime
  }
}

/// Cancellation handle for a presence-graph observation. Releasing (or cancelling) the
/// token stops the underlying observer exactly once.
public final class PresenceGraphObservationToken: @unchecked Sendable {
  private let lock = NSLock()
  private var onCancel: (@Sendable () -> Void)?

  public init(_ onCancel: @escaping @Sendable () -> Void = {}) {
    self.onCancel = onCancel
  }

  /// A token that never fires and stops nothing — the no-transport placeholder.
  public static let inactive = PresenceGraphObservationToken()

  public func cancel() {
    let action: (@Sendable () -> Void)? = lock.withLock {
      let current = onCancel
      onCancel = nil
      return current
    }
    action?()
  }

  deinit {
    cancel()
  }
}
