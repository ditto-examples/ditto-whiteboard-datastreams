import Foundation

/// Local-only fallback transport used when credentials or the Ditto SDK are unavailable. Accepts
/// operations into a `BoundedOperationLog` so the same terminal limits apply as in nearby mesh;
/// previews go nowhere and the mode is always `localPreview`.
public final class InMemoryWhiteboardTransport: WhiteboardTransport, @unchecked Sendable {
  public let localPeerKey: String

  private let lock = NSLock()
  private var foreground = true
  private var knownLog = BoundedOperationLog()
  private var diagnosticsValue: TransportDiagnostics
  private let eventsBroadcast = AsyncBroadcast<TransportEvent>()
  private let diagnosticsBroadcast: AsyncBroadcast<TransportDiagnostics>

  public init(
    localPeerKey: String = "local-\(UUID().uuidString.prefix(8).lowercased())",
    reason: String
  ) {
    self.localPeerKey = localPeerKey
    let initial = TransportDiagnostics(
      localPeerKey: localPeerKey,
      mode: .localPreview,
      connectivityMessage: reason
    )
    diagnosticsValue = initial
    diagnosticsBroadcast = AsyncBroadcast(replayLatest: true, latest: initial)
  }

  public var events: AsyncStream<TransportEvent> { eventsBroadcast.stream }

  public var diagnostics: AsyncStream<TransportDiagnostics> { diagnosticsBroadcast.stream }

  public var currentDiagnostics: TransportDiagnostics {
    lock.withLock { diagnosticsValue }
  }

  public func start(profile: UserProfile) async {
    let running = lock.withLock { foreground }
    updateDiagnostics { $0.running = running }
  }

  public func sendReliable(_ operation: BoardOperation) async -> Bool {
    let result = lock.withLock { knownLog.accept(operation) }
    if case .rejected = result { return false }
    return true
  }

  public func sendLive(_ preview: LivePreview) {}

  public func setForeground(_ isForeground: Bool) {
    lock.withLock { foreground = isForeground }
    updateDiagnostics { $0.running = isForeground }
  }

  /// Local-only preview: the graph is always just this device. Fires once so the presence
  /// viewer renders the "Me" pill, then stays silent.
  public func observePresenceGraph(
    _ handler: @escaping @Sendable (PresenceGraphSnapshot) -> Void
  ) -> PresenceGraphObservationToken {
    let snapshot = PresenceGraphSnapshot(
      localPeer: PresencePeerSnapshot(peerKey: localPeerKey, deviceName: ""),
      remotePeers: []
    )
    Task { handler(snapshot) }
    return PresenceGraphObservationToken()
  }

  public func syncStatusByPeerKey() async -> [String: PeerSyncStatus] { [:] }

  public func close() {
    updateDiagnostics { $0.running = false }
    eventsBroadcast.finish()
  }

  private func updateDiagnostics(_ mutate: (inout TransportDiagnostics) -> Void) {
    let next: TransportDiagnostics? = lock.withLock {
      mutate(&diagnosticsValue)
      return diagnosticsValue
    }
    // MutableStateFlow conflates equal values; keep the same emission discipline.
    if let next, next != diagnosticsBroadcast.latestSnapshot {
      diagnosticsBroadcast.yield(next)
    }
  }
}
