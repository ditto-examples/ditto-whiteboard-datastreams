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
