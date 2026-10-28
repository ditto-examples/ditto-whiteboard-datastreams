import Foundation
import Testing
@testable import WhiteboardCore

final class LockedBox<Value>: @unchecked Sendable {
  private let lock = NSLock()
  private var storage: Value

  init(_ value: Value) {
    storage = value
  }

  @discardableResult
  func withLock<T>(_ body: (inout Value) throws -> T) rethrows -> T {
    try lock.withLock { try body(&storage) }
  }

  var value: Value {
    lock.withLock { storage }
  }
}

/// Polls `condition` until it holds or the timeout elapses. Swift Testing replacement for
/// kotlinx-coroutines-test's `runCurrent`, which deterministically drains pending work.
@discardableResult
func eventually(
  timeoutMillis: UInt64 = 2_000,
  intervalMillis: UInt64 = 5,
  _ condition: @escaping @Sendable () async -> Bool
) async -> Bool {
  let deadline = ContinuousClock.now + .milliseconds(timeoutMillis)
  while ContinuousClock.now < deadline {
    if await condition() { return true }
    try? await Task.sleep(nanoseconds: intervalMillis * 1_000_000)
  }
  return await condition()
}

/// Awaitable gate for handshake-style test coordination.
actor TestGate {
  private var released = false
  private var entered = false
  private var waiters: [CheckedContinuation<Void, Never>] = []

  var hasEntered: Bool { entered }

  func wait() async {
    entered = true
    if released { return }
    await withCheckedContinuation { continuation in
      if released {
        continuation.resume()
      } else {
        waiters.append(continuation)
      }
    }
  }

  func release() {
    released = true
    let pending = waiters
    waiters.removeAll()
    for waiter in pending { waiter.resume() }
  }
}

/// Shared fake mirroring the Kotlin tests' transports: events are delivered manually, diagnostics
/// report running + editingReady once started, and every reliable send succeeds.
final class FakeTransport: WhiteboardTransport, @unchecked Sendable {
  let localPeerKey: String
  private let lock = NSLock()
  private var diagnosticsValue: TransportDiagnostics
  private let eventsBroadcast = AsyncBroadcast<TransportEvent>()
  private let diagnosticsBroadcast: AsyncBroadcast<TransportDiagnostics>
  private(set) var sentLive: [LivePreview] = []

  init(localPeerKey: String) {
    self.localPeerKey = localPeerKey
    let initial = TransportDiagnostics(localPeerKey: localPeerKey)
    diagnosticsValue = initial
    diagnosticsBroadcast = AsyncBroadcast(replayLatest: true, latest: initial)
  }

  var events: AsyncStream<TransportEvent> { eventsBroadcast.stream }
  var diagnostics: AsyncStream<TransportDiagnostics> { diagnosticsBroadcast.stream }
  var currentDiagnostics: TransportDiagnostics { lock.withLock { diagnosticsValue } }

  func start(profile: UserProfile) async {
    lock.withLock {
      diagnosticsValue.running = true
      diagnosticsValue.editingReady = true
    }
    diagnosticsBroadcast.yield(currentDiagnostics)
  }

  func sendReliable(_ operation: BoardOperation) async -> Bool { true }

  func sendLive(_ preview: LivePreview) {
    lock.withLock { sentLive.append(preview) }
  }

  var recordedLive: [LivePreview] { lock.withLock { sentLive } }

  func deliver(_ event: TransportEvent) {
    eventsBroadcast.yield(event)
  }

  func close() {}
}

extension BoardSessionMessages {
  static let test = BoardSessionMessages(
    syncFinishing: "Sync finishing",
    sessionStarting: "Session starting",
    textCannotBeBlank: "Text cannot be blank",
    operationClockExhausted: "Clock exhausted",
    logicalTimeLimit: "Logical-time limit",
    editOutsideSafetyLimits: "Edit outside safety limits",
    terminalResourceLimit: "Terminal resource limit",
    peerLogicalTimeLimit: "Peer logical-time limit",
    visibleObjectLimit: "Visible-object limit",
    clockReservationFailed: "Clock reservation failed",
    remoteUpdateFailed: "Remote update failed"
  )
}
