import Testing
@testable import WhiteboardCore

@Suite("PeerAdmissionTest")
struct PeerAdmissionTest {
  @Test func densePresenceGraphCannotReintroduceIgnoredPeersThroughEdges() {
    let discovered = (1...100).map { String(format: "peer-%03d", $0) }
    let admitted = admittedPeerKeys(localPeerKey: "peer-000", discoveredPeerKeys: discovered)
    let present = admitted.subtracting(["peer-000"])
    let denseConnections = discovered.flatMap { first in
      discovered.map { second in
        PresenceConnection(peer1: first, peer2: second, transport: "LAN")
      }
    }

    let filtered = admittedPresenceConnections(
      localPeerKey: "peer-000",
      presentPeerKeys: present,
      connections: denseConnections
    )

    #expect(admitted.count == maxConnectedPeers)
    #expect(filtered.allSatisfy { admitted.contains($0.peer1) && admitted.contains($0.peer2) })
    #expect(!filtered.contains { $0.peer1 == "peer-100" || $0.peer2 == "peer-100" })
  }

  @Test func localPeerOutsideDeterministicCapacityAdmitsNoAsymmetricSubset() {
    let discovered = (1...maxConnectedPeers).map { "a-\($0)" }
    #expect(admittedPeerKeys(localPeerKey: "z-local", discoveredPeerKeys: discovered).isEmpty)
  }

  @Test func peerTransportAttributionIncludesOnlyDirectPresenceEdges() {
    let connections = [
      PresenceConnection(peer1: "android", peer2: "iphone-duo", transport: "Bluetooth LE"),
      PresenceConnection(peer1: "iphone-duo", peer2: "ui-tester", transport: "P2P WiFi"),
      PresenceConnection(peer1: "ui-tester", peer2: "android", transport: "LAN"),
    ]

    #expect(
      directPresenceTransports(
        localPeerKey: "android",
        remotePeerKey: "iphone-duo",
        connections: connections
      ) == ["Bluetooth LE"]
    )
    #expect(
      directPresenceTransports(
        localPeerKey: "android",
        remotePeerKey: "ui-tester",
        connections: connections
      ) == ["LAN"]
    )
  }
}
