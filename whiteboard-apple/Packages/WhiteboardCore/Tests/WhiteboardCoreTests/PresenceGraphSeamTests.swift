import Foundation
import Testing

@testable import WhiteboardCore

@Suite("Presence graph seam")
struct PresenceGraphSeamTests {
  @Test("InMemory observePresenceGraph fires once with a local-only graph")
  func inMemoryFiresOnceWithLocalOnlyGraph() async {
    let transport = InMemoryWhiteboardTransport(localPeerKey: "local-test", reason: "preview")
    await confirmation("handler fires exactly once", expectedCount: 1) { confirm in
      let token = transport.observePresenceGraph { snapshot in
        #expect(snapshot.localPeer.peerKey == "local-test")
        #expect(snapshot.remotePeers.isEmpty)
        confirm()
      }
      // The fire-once push is dispatched asynchronously; keep the token alive and
      // give it a runloop turn.
      try? await Task.sleep(for: .milliseconds(100))
      _ = token
    }
  }

  @Test("InMemory syncStatusByPeerKey is empty")
  func inMemorySyncStatusEmpty() async {
    let transport = InMemoryWhiteboardTransport(reason: "preview")
    #expect(await transport.syncStatusByPeerKey() == [:])
  }

  @Test("The default protocol seam never fires and reports no sync rows")
  func defaultSeamIsInert() async {
    struct BareTransport: WhiteboardTransport {
      var localPeerKey: String { "bare" }
      var events: AsyncStream<TransportEvent> { AsyncStream { $0.finish() } }
      var diagnostics: AsyncStream<TransportDiagnostics> { AsyncStream { $0.finish() } }
      var currentDiagnostics: TransportDiagnostics { TransportDiagnostics() }
      func start(profile: UserProfile) async {}
      func sendReliable(_ operation: BoardOperation) async -> Bool { true }
      func sendLive(_ preview: LivePreview) {}
      func close() {}
    }
    let transport = BareTransport()
    let token = transport.observePresenceGraph { _ in
      Issue.record("default observePresenceGraph must never fire")
    }
    #expect(token === PresenceGraphObservationToken.inactive)
    #expect(await transport.syncStatusByPeerKey() == [:])
  }

  @Test("Token cancel runs the stop action exactly once, including on deinit")
  func tokenCancelsOnce() {
    final class Counter: @unchecked Sendable {
      private(set) var count = 0
      func increment() { count += 1 }
    }
    let counter = Counter()
    do {
      let token = PresenceGraphObservationToken { counter.increment() }
      token.cancel()
      token.cancel()
      #expect(counter.count == 1)
    }
    // Deinit of an already-cancelled token must not run the action again.
    #expect(counter.count == 1)
  }
}
