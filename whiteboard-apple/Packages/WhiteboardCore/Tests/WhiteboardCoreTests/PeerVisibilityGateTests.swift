import Foundation
import Testing
@testable import WhiteboardCore

@Suite("PeerVisibilityGateTest")
struct PeerVisibilityGateTest {
  @Test func departureCleanupCannotBeOvertakenByLateResourceCreation() {
    let gate = PeerVisibilityGate()
    let resources = LockedBox<Set<String>>([])
    gate.update(["peer"]) { _ in }
    gate.enable()
    let creationEntered = DispatchSemaphore(value: 0)
    let releaseCreation = DispatchSemaphore(value: 0)
    let creationDone = DispatchSemaphore(value: 0)
    let removalDone = DispatchSemaphore(value: 0)
    let creationResult = LockedBox<String?>(nil)

    DispatchQueue.global().async {
      let result: String? = gate.ifVisible("peer") {
        creationEntered.signal()
        _ = releaseCreation.wait(timeout: .now() + 2)
        resources.withLock { $0.insert("peer") }
        return "created"
      }
      creationResult.withLock { $0 = result }
      creationDone.signal()
    }
    #expect(creationEntered.wait(timeout: .now() + 2) == .success)
    DispatchQueue.global().async {
      gate.update([]) { removed in
        resources.withLock { $0.subtract(removed) }
      }
      removalDone.signal()
    }
    releaseCreation.signal()

    #expect(creationDone.wait(timeout: .now() + 2) == .success)
    #expect(removalDone.wait(timeout: .now() + 2) == .success)
    #expect(creationResult.value == "created")
    #expect(resources.value.isEmpty)
    #expect(gate.ifVisible("peer") { "recreated" } == nil)
  }

  @Test func pausedGateCannotRecreateResourcesForStillVisiblePeer() {
    let gate = PeerVisibilityGate()
    let resources = LockedBox<Set<String>>([])
    gate.update(["peer"]) { _ in }
    gate.enable()
    #expect(
      gate.ifVisible("peer") {
        resources.withLock { $0.insert("peer") }
        return "created"
      } == "created"
    )

    gate.disable {
      resources.withLock { $0.removeAll() }
    }

    #expect(
      gate.ifVisible("peer") {
        resources.withLock { $0.insert("peer") }
        return "recreated"
      } == nil
    )
    #expect(resources.value.isEmpty)
  }
}
