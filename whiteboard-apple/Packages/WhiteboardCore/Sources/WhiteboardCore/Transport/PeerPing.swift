import Foundation

/// Wire-compatible with the Rust Data Streams ping example. No board state is involved.
public enum PeerPing {
  public static let topic = "ping"
  public static let count = 5
  public static let payloadSize = 64
  public static let maximumPayloadSize = 1_024

  public static func request(sequence: UInt64) -> Data {
    var bytes = Data(repeating: 0, count: payloadSize)
    for index in 0..<8 { bytes[index] = UInt8(truncatingIfNeeded: sequence >> ((7 - index) * 8)) }
    return bytes
  }

  public static func measure(
    sequence: UInt64,
    exchange: @Sendable (Data) async throws -> Data
  ) async throws -> Double {
    let request = request(sequence: sequence)
    let clock = ContinuousClock()
    let started = clock.now
    let response = try await exchange(request)
    try Task.checkCancellation()
    guard response == request else { throw PeerPingError.unexpectedEcho }
    let elapsed = started.duration(to: clock.now).components
    return Double(elapsed.seconds) * 1_000 + Double(elapsed.attoseconds) / 1e15
  }
}

public enum PeerPingError: Error, LocalizedError, Equatable, Sendable {
  case unavailable, busy, connectionFailed, timedOut, closed, unexpectedEcho, tooLarge

  public var errorDescription: String? {
    switch self {
    case .unavailable: "Nearby sync must be running to ping a peer."
    case .busy: "A ping is already running."
    case .connectionFailed: "Could not connect. Keep the other app open and check that it supports Peer ping."
    case .timedOut: "No echo within 5 seconds. The peer may have disconnected or paused."
    case .closed: "The ping stream closed before the echo arrived."
    case .unexpectedEcho: "The response did not match the request."
    case .tooLarge: "The stream cannot carry the ping payload."
    }
  }
}
