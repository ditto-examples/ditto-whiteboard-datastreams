import Foundation

/// Bounded, serialized application lanes keyed by peer.
///
/// A slow BoardSession acknowledgement from one peer cannot occupy the global reliable decoder or
/// delay another peer's Hello/snapshot traffic, while operations from the same peer remain ordered.
public final class PerPeerOperationDispatcher<Value: Sendable>: @unchecked Sendable {
  private final class Lane: @unchecked Sendable {
    let peerKey: String
    let capacity: Int
    let handle: @Sendable (String, Value) async -> Void
    let lock = NSLock()
    var buffer: [Value] = []
    var draining = false
    var closed = false
    var drainTask: Task<Void, Never>?

    init(peerKey: String, capacity: Int, handle: @escaping @Sendable (String, Value) async -> Void) {
      self.peerKey = peerKey
      self.capacity = capacity
      self.handle = handle
    }

    func enqueue(_ value: Value) -> Bool {
      lock.lock()
      guard !closed, buffer.count < capacity else {
        lock.unlock()
        return false
      }
      buffer.append(value)
      if !draining {
        draining = true
        drainTask = Task { await self.drain() }
      }
      lock.unlock()
      return true
    }

    private func drain() async {
      while true {
        let next: Value? = lock.withLock {
          if closed || buffer.isEmpty {
            draining = false
            drainTask = nil
            return nil
          }
          return buffer.removeFirst()
        }
        guard let value = next else { return }
        await handle(peerKey, value)
        if Task.isCancelled {
          lock.withLock {
            buffer.removeAll()
            draining = false
            drainTask = nil
          }
          return
        }
      }
    }

    func close() {
      lock.lock()
      closed = true
      buffer.removeAll()
      let task = drainTask
      drainTask = nil
      draining = false
      lock.unlock()
      task?.cancel()
    }
  }

  private let capacityPerPeer: Int
  private let handle: @Sendable (String, Value) async -> Void
  private let lock = NSLock()
  private var lanes: [String: Lane] = [:]

  public init(
    capacityPerPeer: Int,
    handle: @escaping @Sendable (String, Value) async -> Void
  ) {
    self.capacityPerPeer = capacityPerPeer
    self.handle = handle
  }

  @discardableResult
  public func tryDispatch(_ peerKey: String, _ value: Value) -> Bool {
    let lane: Lane = lock.withLock {
      if let existing = lanes[peerKey] { return existing }
      let created = Lane(peerKey: peerKey, capacity: capacityPerPeer, handle: handle)
      lanes[peerKey] = created
      return created
    }
    return lane.enqueue(value)
  }

  public func remove(_ peerKey: String) {
    let lane = lock.withLock { lanes.removeValue(forKey: peerKey) }
    lane?.close()
  }

  public func retainPeers(_ peerKeys: Set<String>) {
    let doomed = lock.withLock { () -> [Lane] in
      let removed = lanes.filter { !peerKeys.contains($0.key) }
      lanes = lanes.filter { peerKeys.contains($0.key) }
      return Array(removed.values)
    }
    for lane in doomed { lane.close() }
  }

  public func close() {
    let all = lock.withLock { () -> [Lane] in
      let values = Array(lanes.values)
      lanes.removeAll()
      return values
    }
    for lane in all { lane.close() }
  }

  deinit {
    close()
  }
}
