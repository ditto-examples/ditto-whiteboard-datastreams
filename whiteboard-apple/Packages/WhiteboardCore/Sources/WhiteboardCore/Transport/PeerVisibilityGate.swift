import Foundation

/// Serializes peer-scoped resource creation with presence removal.
///
/// Without this gate, a sender can pass a visibility check, presence can tear down the peer, and
/// the sender can then recreate an outbox that no future presence update knows to remove.
public final class PeerVisibilityGate: @unchecked Sendable {
  private let lock = NSLock()
  private var visiblePeers: Set<String> = []
  private var enabled = false

  public init() {}

  public func update(_ peers: Set<String>, onUpdated: (_ removedPeers: Set<String>) -> Void) {
    lock.lock()
    let removed = visiblePeers.subtracting(peers)
    visiblePeers = peers
    onUpdated(removed)
    lock.unlock()
  }

  public func ifVisible<T>(_ peer: String, block: () throws -> T) rethrows -> T? {
    lock.lock()
    defer { lock.unlock() }
    guard enabled, visiblePeers.contains(peer) else { return nil }
    return try block()
  }

  public func enable() {
    lock.withLock { enabled = true }
  }

  public func disable(onDisabled: () -> Void) {
    lock.lock()
    enabled = false
    onDisabled()
    lock.unlock()
  }
}
