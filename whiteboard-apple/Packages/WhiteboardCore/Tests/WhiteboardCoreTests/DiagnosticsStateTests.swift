import Foundation
import Testing
@testable import WhiteboardCore

@Suite("DiagnosticsStateTest")
struct DiagnosticsStateTest {
  private func newState() -> DiagnosticsState {
    DiagnosticsState(initial: TransportDiagnostics(localPeerKey: "local"))
  }

  @Test func updatePeerCreatesThenMutatesEntry() async {
    let state = newState()
    await state.updatePeer("B") { peer in
      var peer = peer
      peer.displayName = "Bob"
      return peer
    }
    #expect(await state.value.peers["B"]?.displayName == "Bob")

    await state.updatePeer("B") { peer in
      var peer = peer
      peer.transmitMessagesPerSecond = 4.0
      return peer
    }
    // The follow-up mutation must preserve the earlier field, not reset it.
    let value = await state.value
    #expect(value.peers["B"]?.displayName == "Bob")
    #expect(value.peers["B"]?.transmitMessagesPerSecond == 4.0)
  }

  @Test func retainPeersDropsDepartedPeers() async {
    let state = newState()
    await state.updatePeer("A") { peer in
      var peer = peer
      peer.displayName = "Amy"
      return peer
    }
    await state.updatePeer("B") { peer in
      var peer = peer
      peer.displayName = "Bob"
      return peer
    }

    await state.retainPeers(["A"])

    let value = await state.value
    #expect(value.peers["A"] != nil)
    #expect(value.peers["B"] == nil)
  }

  @Test func retainPeersOnEmptyVisibleSetClearsEverything() async {
    let state = newState()
    await state.updatePeer("A") { peer in
      var peer = peer
      peer.displayName = "Amy"
      return peer
    }
    await state.retainPeers([])
    #expect(await state.value.peers.isEmpty)
  }

  @Test func concurrentUpdatesForDistinctPeersDoNotLoseEntries() async {
    let state = newState()
    let peerCount = 200
    await withTaskGroup(of: Void.self) { group in
      for index in 0..<peerCount {
        group.addTask {
          let peer = "peer-\(index)"
          // Two mutations per peer so interleaved read-modify-writes have to compose atomically.
          await state.updatePeer(peer) { entry in
            var entry = entry
            entry.displayName = "name-\(index)"
            return entry
          }
          await state.updatePeer(peer) { entry in
            var entry = entry
            entry.transmitMessagesPerSecond = Double(index)
            return entry
          }
        }
      }
    }

    let value = await state.value
    #expect(value.peers.count == peerCount)
    for index in 0..<peerCount {
      let peer = value.peers["peer-\(index)"]
      #expect(peer?.displayName == "name-\(index)")
      #expect(peer?.transmitMessagesPerSecond == Double(index))
    }
  }

  @Test func ghostPeerIsNotResurrectedAfterRemoval() async {
    // Reproduces the reported hazard shape: a peer that once had traffic is removed on presence
    // change (retainPeers), and a later diagnostics write for a still-visible peer must not bring
    // the departed peer back.
    let state = newState()
    await state.updatePeer("A") { peer in
      var peer = peer
      peer.displayName = "Amy"
      return peer
    }
    await state.updatePeer("ghost") { peer in
      var peer = peer
      peer.transmitMessagesPerSecond = 9.0
      return peer
    }

    await state.retainPeers(["A"])
    #expect(await state.value.peers["ghost"] == nil)

    await state.updatePeer("A") { peer in
      var peer = peer
      peer.receiveMessagesPerSecond = 1.0
      return peer
    }
    let value = await state.value
    #expect(value.peers["ghost"] == nil)
    #expect(Set(value.peers.keys) == ["A"])
  }
}
