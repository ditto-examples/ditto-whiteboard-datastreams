import Foundation
import Testing
@testable import WhiteboardCore

@Suite("PerPeerOperationDispatcherTest")
struct PerPeerOperationDispatcherTest {
  @Test func stalledPeerDoesNotBlockAnotherPeerAndEachLaneStaysOrdered() async {
    let releaseFirstPeer = TestGate()
    let handled = LockedBox<[String]>([])
    let dispatcher = PerPeerOperationDispatcher<String>(capacityPerPeer: 2) { peer, value in
      if peer == "slow" && value == "one" { await releaseFirstPeer.wait() }
      handled.withLock { $0.append("\(peer):\(value)") }
    }

    #expect(dispatcher.tryDispatch("slow", "one"))
    #expect(dispatcher.tryDispatch("slow", "two"))
    #expect(dispatcher.tryDispatch("healthy", "hello"))

    #expect(await eventually {
      await releaseFirstPeer.hasEntered && handled.value == ["healthy:hello"]
    })
    await releaseFirstPeer.release()
    #expect(await eventually {
      handled.value == ["healthy:hello", "slow:one", "slow:two"]
    })
    dispatcher.close()
  }
}
