import Foundation
import Testing
@testable import WhiteboardCore

struct PeerPingTests {
  @Test func `Request uses the Rust big endian sequence and zero padding`() {
    let request = PeerPing.request(sequence: 0x0102030405060708)
    #expect(request.count == 64)
    #expect(Array(request.prefix(8)) == [1, 2, 3, 4, 5, 6, 7, 8])
    #expect(request.dropFirst(8).allSatisfy { $0 == 0 })
  }

  @Test func `Matching echo produces a nonnegative round trip time`() async throws {
    let result = try await PeerPing.measure(sequence: 3) { $0 }
    #expect(result >= 0)
  }

  @Test(arguments: [0, 7, 63])
  func `Corruption anywhere in the payload rejects the sample`(offset: Int) async {
    await #expect(throws: PeerPingError.unexpectedEcho) {
      _ = try await PeerPing.measure(sequence: 3) { request in
        var corrupt = request
        corrupt[offset] ^= 1
        return corrupt
      }
    }
  }

  @Test func `Truncated response rejects the sample`() async {
    await #expect(throws: PeerPingError.unexpectedEcho) {
      _ = try await PeerPing.measure(sequence: 0) { Data($0.prefix(8)) }
    }
  }

  @Test func `Transport failure propagates without a successful sample`() async {
    await #expect(throws: PeerPingError.timedOut) {
      _ = try await PeerPing.measure(sequence: 0) { _ in throw PeerPingError.timedOut }
    }
  }

  @Test func `Cancellation propagates`() async {
    await #expect(throws: CancellationError.self) {
      _ = try await PeerPing.measure(sequence: 0) { _ in throw CancellationError() }
    }
  }
}
