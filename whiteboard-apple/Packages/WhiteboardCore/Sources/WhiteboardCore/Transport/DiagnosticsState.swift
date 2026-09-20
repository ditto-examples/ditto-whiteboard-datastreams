import Foundation

/// Owns the transport's `TransportDiagnostics` snapshot and guarantees every mutation is atomic.
/// The transport touches diagnostics from many tasks at once (reliable/live/rate/presence workers
/// plus connect/outbox/bind callbacks); a plain read-modify-write would let overlapping writers
/// clobber each other and silently drop peer entries, rates, snapshot progress or error text.
/// Actor isolation keeps the same lost-update discipline `BoardSession` uses for its board state.
public actor DiagnosticsState {
  public private(set) var value: TransportDiagnostics
  private var continuations: [UUID: AsyncStream<TransportDiagnostics>.Continuation] = [:]

  public init(initial: TransportDiagnostics) {
    value = initial
  }

  /// StateFlow-like stream: replays the current snapshot, then subsequent changes.
  public var updates: AsyncStream<TransportDiagnostics> {
    let (stream, continuation) = AsyncStream<TransportDiagnostics>.makeStream()
    let id = UUID()
    continuations[id] = continuation
    continuation.onTermination = { [weak self] _ in
      Task { await self?.removeContinuation(id) }
    }
    continuation.yield(value)
    return stream
  }

  /// Atomically mutate the whole diagnostics snapshot.
  public func update(_ transform: (TransportDiagnostics) -> TransportDiagnostics) {
    let next = transform(value)
    guard next != value else { return }
    value = next
    for continuation in continuations.values { continuation.yield(next) }
  }

  /// Atomically create-or-update a single peer entry.
  public func updatePeer(_ peer: String, _ update: (PeerDiagnostics) -> PeerDiagnostics) {
    self.update { current in
      var next = current
      next.peers[peer] = update(current.peers[peer] ?? PeerDiagnostics(peerKey: peer))
      return next
    }
  }

  /// Drop every peer entry whose key is not in `visiblePeers`. Called when presence changes so a
  /// peer that left the mesh cannot linger in the connected-people count or presence graph.
  public func retainPeers(_ visiblePeers: Set<String>) {
    self.update { current in
      guard !current.peers.keys.allSatisfy({ visiblePeers.contains($0) }) else { return current }
      var next = current
      next.peers = next.peers.filter { visiblePeers.contains($0.key) }
      return next
    }
  }

  private func removeContinuation(_ id: UUID) {
    continuations[id] = nil
  }
}
