import Foundation

/// First-completion-wins Boolean handshake slot, mirroring Kotlin's
/// `CompletableDeferred<Boolean>?` on the transport-facing event contract. Transports hand these
/// to `BoardSession` so the session can acknowledge the prepare/commit/apply phases of reliable
/// ingress; a transport may also fail one to signal it aborted mid-handshake.
public final class HandshakeSignal: @unchecked Sendable {
  private enum State {
    case pending
    case completed(Bool)
    case failed(any Error)
  }

  private let lock = NSLock()
  private var state = State.pending
  private var waiters: [UUID: CheckedContinuation<Bool, any Error>] = [:]

  public init() {}

  public init(completed value: Bool) {
    state = .completed(value)
  }

  @discardableResult
  public func complete(_ value: Bool) -> Bool {
    lock.lock()
    guard case .pending = state else {
      lock.unlock()
      return false
    }
    state = .completed(value)
    let pending = waiters
    waiters.removeAll()
    lock.unlock()
    for waiter in pending.values { waiter.resume(returning: value) }
    return true
  }

  @discardableResult
  public func fail(_ error: any Error) -> Bool {
    lock.lock()
    guard case .pending = state else {
      lock.unlock()
      return false
    }
    state = .failed(error)
    let pending = waiters
    waiters.removeAll()
    lock.unlock()
    for waiter in pending.values { waiter.resume(throwing: error) }
    return true
  }

  public func wait() async throws -> Bool {
    try Task.checkCancellation()
    let id = UUID()
    return try await withTaskCancellationHandler {
      try await withCheckedThrowingContinuation { continuation in
        lock.lock()
        switch state {
        case .completed(let value):
          lock.unlock()
          continuation.resume(returning: value)
        case .failed(let error):
          lock.unlock()
          continuation.resume(throwing: error)
        case .pending:
          waiters[id] = continuation
          lock.unlock()
        }
      }
    } onCancel: {
      lock.lock()
      let waiter = waiters.removeValue(forKey: id)
      lock.unlock()
      waiter?.resume(throwing: CancellationError())
    }
  }
}

/// The seam between the app and the network. A Ditto Data Streams implementation is the real
/// transport; `InMemoryWhiteboardTransport` is the local-only fallback used when credentials or
/// the SDK are unavailable. Callers observe `events` and `diagnostics` and push work in with
/// `sendReliable` (ordered, durable board operations) and `sendLive` (lossy previews).
public protocol WhiteboardTransport: Sendable {
  var localPeerKey: String { get }
  /// Multicast stream of inbound transport events; each access returns a fresh subscriber stream.
  var events: AsyncStream<TransportEvent> { get }
  /// StateFlow-like stream: each access returns a fresh subscriber stream that first replays the
  /// current diagnostics snapshot, then subsequent changes.
  var diagnostics: AsyncStream<TransportDiagnostics> { get }
  /// Synchronous read of the latest diagnostics snapshot (Kotlin `StateFlow.value`).
  var currentDiagnostics: TransportDiagnostics { get }
  var requiredPermissions: [String] { get }

  func start(profile: UserProfile) async
  /// Atomically reserves the operation in the authoritative reconciliation log.
  func sendReliable(_ operation: BoardOperation) async -> Bool
  func sendLive(_ preview: LivePreview)
  func resolvePermissions(allGranted: Bool)
  /// Starts or pauses nearby networking while retaining in-memory board state.
  func setForeground(_ isForeground: Bool)
  /// Observes the raw presence graph — ALL peers, unfiltered. The session's own stream
  /// topology deliberately filters presence to admitted whiteboard peers; the presence
  /// viewer needs the full mesh (Edge Studio parity), so it uses this separate entry
  /// point. The handler may fire on a background thread. Cancel (or release) the
  /// returned token to stop observing.
  func observePresenceGraph(
    _ handler: @escaping @Sendable (PresenceGraphSnapshot) -> Void
  ) -> PresenceGraphObservationToken
  /// Best-effort read of `system:data_sync_info`, keyed by peer key. Empty when the
  /// local store cannot answer (the Ditto identity is SmallPeersOnly offline, but the
  /// query still runs against the local store).
  func syncStatusByPeerKey() async -> [String: PeerSyncStatus]
  func close()
}

extension WhiteboardTransport {
  public var requiredPermissions: [String] { [] }
  public func resolvePermissions(allGranted: Bool) {}
  public func setForeground(_ isForeground: Bool) {}
  public func observePresenceGraph(
    _ handler: @escaping @Sendable (PresenceGraphSnapshot) -> Void
  ) -> PresenceGraphObservationToken {
    .inactive
  }
  public func syncStatusByPeerKey() async -> [String: PeerSyncStatus] { [:] }
}

public enum TransportEvent: Sendable {
  case reliableOperationReceived(
    operation: BoardOperation,
    prepared: HandshakeSignal?,
    committed: HandshakeSignal?,
    applied: HandshakeSignal?
  )
  case livePreviewReceived(LivePreview)
  case snapshotMerged(
    operations: [BoardOperation],
    prepared: HandshakeSignal?,
    committed: HandshakeSignal?,
    applied: HandshakeSignal?
  )
  case incompatiblePeer(peerKey: String, protocolVersion: Int)
}

public struct PeerDiagnostics: Equatable, Sendable {
  public var peerKey: String
  public var displayName: String?
  public var colorArgb: Int32?
  public var transports: Set<String>
  public var liveConnected: Bool
  public var stateConnected: Bool
  public var snapshotStatus: SnapshotStatus
  public var snapshotProgress: Float
  public var transmitMessagesPerSecond: Double
  public var receiveMessagesPerSecond: Double
  public var lastError: String?

  public init(
    peerKey: String,
    displayName: String? = nil,
    colorArgb: Int32? = nil,
    transports: Set<String> = [],
    liveConnected: Bool = false,
    stateConnected: Bool = false,
    snapshotStatus: SnapshotStatus = .idle,
    snapshotProgress: Float = 0,
    transmitMessagesPerSecond: Double = 0,
    receiveMessagesPerSecond: Double = 0,
    lastError: String? = nil
  ) {
    self.peerKey = peerKey
    self.displayName = displayName
    self.colorArgb = colorArgb
    self.transports = transports
    self.liveConnected = liveConnected
    self.stateConnected = stateConnected
    self.snapshotStatus = snapshotStatus
    self.snapshotProgress = snapshotProgress
    self.transmitMessagesPerSecond = transmitMessagesPerSecond
    self.receiveMessagesPerSecond = receiveMessagesPerSecond
    self.lastError = lastError
  }
}

public enum SnapshotStatus: String, Equatable, Sendable, CaseIterable {
  case idle
  /// Offer refused because the remote peer's single hydration slot is busy; waiting to re-offer.
  case queued
  case receiving
  case merged
  case rejected
  case acknowledged
  case sending
}

public enum TransportMode: String, Equatable, Sendable, CaseIterable {
  case localPreview
  case nearbyMesh
}

public struct TransportDiagnostics: Equatable, Sendable {
  public var localPeerKey: String
  public var running: Bool
  /// False only while a newly discovered peer's initial reconciliation is unfinished.
  public var editingReady: Bool
  public var mode: TransportMode
  public var connectivityMessage: String?
  public var peers: [String: PeerDiagnostics]
  public var presenceConnections: Set<PresenceConnection>
  public var incompatiblePeers: [String: Int]

  public init(
    localPeerKey: String = "local",
    running: Bool = false,
    editingReady: Bool = true,
    mode: TransportMode = .localPreview,
    connectivityMessage: String? = nil,
    peers: [String: PeerDiagnostics] = [:],
    presenceConnections: Set<PresenceConnection> = [],
    incompatiblePeers: [String: Int] = [:]
  ) {
    self.localPeerKey = localPeerKey
    self.running = running
    self.editingReady = editingReady
    self.mode = mode
    self.connectivityMessage = connectivityMessage
    self.peers = peers
    self.presenceConnections = presenceConnections
    self.incompatiblePeers = incompatiblePeers
  }
}
