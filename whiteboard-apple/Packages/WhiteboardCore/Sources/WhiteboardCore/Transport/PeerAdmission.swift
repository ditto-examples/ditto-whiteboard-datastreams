public let maxConnectedPeers = 10

public struct PresenceConnection: Equatable, Hashable, Sendable {
  public var peer1: String
  public var peer2: String
  public var transport: String

  public init(peer1: String, peer2: String, transport: String) {
    self.peer1 = peer1
    self.peer2 = peer2
    self.transport = transport
  }
}

public func admittedPeerKeys(
  localPeerKey: String,
  discoveredPeerKeys: some Collection<String>
) -> Set<String> {
  let admitted = Set((discoveredPeerKeys + [localPeerKey]).sorted().prefix(maxConnectedPeers))
  return admitted.contains(localPeerKey) ? admitted : []
}

public func admittedPresenceConnections(
  localPeerKey: String,
  presentPeerKeys: Set<String>,
  connections: some Sequence<PresenceConnection>
) -> Set<PresenceConnection> {
  let admittedGraphKeys = presentPeerKeys.union([localPeerKey])
  return Set(connections.filter {
    admittedGraphKeys.contains($0.peer1) && admittedGraphKeys.contains($0.peer2)
  })
}

/// Returns only the Presence Graph transport edges directly connecting the local and remote
/// peers. A remote peer's `connections` collection describes its entire view of the mesh, so an
/// unfiltered collection can incorrectly attribute a connection between two other peers (such as
/// an Apple-to-Apple P2P Wi-Fi edge) to the local device.
public func directPresenceTransports(
  localPeerKey: String,
  remotePeerKey: String,
  connections: some Sequence<PresenceConnection>
) -> Set<String> {
  Set(connections.compactMap { connection in
    let directlyConnected =
      (connection.peer1 == localPeerKey && connection.peer2 == remotePeerKey) ||
      (connection.peer1 == remotePeerKey && connection.peer2 == localPeerKey)
    return directlyConnected ? connection.transport : nil
  })
}
