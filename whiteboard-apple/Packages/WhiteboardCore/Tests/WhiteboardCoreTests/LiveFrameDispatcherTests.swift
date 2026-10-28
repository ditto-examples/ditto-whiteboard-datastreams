import Foundation
import Testing
@testable import WhiteboardCore

private struct StreamClosed: Error {}

@Suite("LiveFrameDispatcherTest")
struct LiveFrameDispatcherTest {
  @Test func closedRecipientDoesNotKillDispatcherOrBlockHealthyRecipient() async throws {
    let (frames, continuation) = AsyncStream<Int>.makeStream()
    continuation.yield(1)
    continuation.yield(2)
    continuation.finish()
    let sent = LockedBox<[(String, Int)]>([])
    let failures = LockedBox<[String?]>([])

    try await dispatchLiveFrames(
      frames: frames,
      encode: { frame in Data([UInt8(frame)]) },
      recipients: { ["closed", "healthy"] },
      maxSendSize: { recipient in
        if recipient == "closed" { throw StreamClosed() }
        return 64
      },
      send: { recipient, bytes in
        sent.withLock { $0.append((recipient, Int(bytes[bytes.startIndex]))) }
      },
      onSent: { _ in },
      onFailure: { recipient, _ in
        failures.withLock { $0.append(recipient) }
      },
      throttle: {}
    )

    #expect(sent.value.map { "\($0.0):\($0.1)" } == ["healthy:1", "healthy:2"])
    #expect(failures.value == ["closed", "closed"])
  }
}
