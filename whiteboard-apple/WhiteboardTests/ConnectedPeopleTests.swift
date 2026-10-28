import Testing
import WhiteboardCore

@testable import Whiteboard

struct ConnectedPeopleTests {
  @Test func `roster contains local and currently present compatible peers only`() throws {
    let board = BoardState(
      profiles: [
        "local": try UserProfile(peerKey: "local", displayName: "Ada", colorArgb: whiteboardPalette[1]),
        "connected": try UserProfile(
          peerKey: "connected", displayName: "Grace", colorArgb: whiteboardPalette[2]),
        "stale": try UserProfile(
          peerKey: "stale", displayName: "Linus", colorArgb: whiteboardPalette[3]),
      ]
    )
    let diagnostics = TransportDiagnostics(
      localPeerKey: "local",
      running: true,
      peers: [
        "connected": PeerDiagnostics(peerKey: "connected", transports: ["LAN"]),
        "incompatible": PeerDiagnostics(peerKey: "incompatible", displayName: "Old app"),
      ],
      incompatiblePeers: ["incompatible": 1]
    )

    let people = connectedPeople(
      board: board,
      diagnostics: diagnostics,
      localColorArgb: whiteboardPalette[1]
    )

    #expect(people.map(\.displayName) == ["Ada", "Grace"])
    #expect(people.last?.colorArgb == whiteboardPalette[2])
    #expect(!people.contains { $0.peerKey == "stale" || $0.peerKey == "incompatible" })
  }

  @Test func `roster uses presence color while profile is still syncing`() {
    let diagnostics = TransportDiagnostics(
      localPeerKey: "local",
      peers: [
        "remote": PeerDiagnostics(
          peerKey: "remote",
          displayName: "Nearby artist",
          colorArgb: whiteboardPalette[4]
        )
      ]
    )

    let people = connectedPeople(
      board: BoardState(),
      diagnostics: diagnostics,
      localColorArgb: whiteboardPalette[0]
    )

    #expect(people.last?.colorArgb == whiteboardPalette[4])
  }

  @Test func `remote people sort case insensitively with peer key tiebreak`() {
    let diagnostics = TransportDiagnostics(
      localPeerKey: "local",
      peers: [
        "peer-b": PeerDiagnostics(peerKey: "peer-b", displayName: "zelda"),
        "peer-a": PeerDiagnostics(peerKey: "peer-a", displayName: "Aaron"),
        "peer-c": PeerDiagnostics(peerKey: "peer-c", displayName: "aaron"),
      ]
    )

    let people = connectedPeople(
      board: BoardState(),
      diagnostics: diagnostics,
      localColorArgb: whiteboardPalette[0]
    )

    #expect(people.map(\.peerKey) == ["local", "peer-a", "peer-c", "peer-b"])
  }
}
