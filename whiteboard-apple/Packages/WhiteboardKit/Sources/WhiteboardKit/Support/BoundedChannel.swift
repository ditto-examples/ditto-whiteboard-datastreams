import Foundation

/// Bounded single-consumer channel mirroring the Kotlin `Channel` semantics the transport
/// relies on: `send` suspends while full (`.suspend` policy), `trySend` never suspends, and
/// `.dropOldest` evicts the oldest buffered element instead of applying backpressure.
/// Closing resumes suspended producers with `false` and lets the consumer drain remaining
/// elements before terminating, matching `Channel.close()`.
final class BoundedChannel<Element: Sendable>: AsyncSequence, @unchecked Sendable {
  enum Policy: Sendable {
    case suspend
    case dropOldest
  }

  // Both boxes are only mutated under `lock`; Sendable is asserted so the cancellation-handler
  // closures (which are @Sendable) may capture them.
  private final class ProducerBox: @unchecked Sendable {
    var resumed = false
    var continuation: CheckedContinuation<Bool, Never>?
  }

  private final class ConsumerBox: @unchecked Sendable {
    var resumed = false
    var continuation: CheckedContinuation<Element?, Never>?
  }

  private let capacity: Int
  private let policy: Policy
  private let lock = NSLock()
  private var buffer: [Element] = []
  private var closed = false
  private var consumer: ConsumerBox?
  private var producers: [UUID: ProducerBox] = [:]

  init(capacity: Int, policy: Policy = .suspend) {
    precondition(capacity > 0)
    self.capacity = capacity
    self.policy = policy
  }

  func trySend(_ element: Element) -> Bool {
    lock.lock()
    if closed {
      lock.unlock()
      return false
    }
    if let consumer, !consumer.resumed {
      self.consumer = nil
      consumer.resumed = true
      let continuation = consumer.continuation
      lock.unlock()
      continuation?.resume(returning: element)
      return true
    }
    switch policy {
    case .suspend:
      guard buffer.count < capacity else {
        lock.unlock()
        return false
      }
      buffer.append(element)
    case .dropOldest:
      if buffer.count >= capacity { buffer.removeFirst() }
      buffer.append(element)
    }
    lock.unlock()
    return true
  }

  /// Suspends while the channel is full (`.suspend` policy). Returns `false` when the element
  /// could not be delivered because the channel was closed (or the send was cancelled).
  @discardableResult
  func send(_ element: Element) async -> Bool {
    if trySend(element) { return true }
    let id = UUID()
    let box = ProducerBox()
    return await withTaskCancellationHandler {
      await withCheckedContinuation { continuation in
        lock.lock()
        if box.resumed || closed {
          lock.unlock()
          continuation.resume(returning: false)
          return
        }
        box.continuation = continuation
        producers[id] = box
        lock.unlock()
      }
    } onCancel: {
      lock.lock()
      guard let box = producers.removeValue(forKey: id), !box.resumed else {
        lock.unlock()
        return
      }
      box.resumed = true
      let continuation = box.continuation
      lock.unlock()
      continuation?.resume(returning: false)
    }
  }

  func close() {
    lock.lock()
    guard !closed else {
      lock.unlock()
      return
    }
    closed = true
    let pendingProducers = producers
    producers.removeAll()
    let waitingConsumer = buffer.isEmpty ? consumer : nil
    if buffer.isEmpty { consumer = nil }
    if let waitingConsumer { waitingConsumer.resumed = true }
    lock.unlock()
    for box in pendingProducers.values {
      box.resumed = true
      box.continuation?.resume(returning: false)
    }
    waitingConsumer?.continuation?.resume(returning: nil)
  }

  private func receive() async -> Element? {
    let box = ConsumerBox()
    return await withTaskCancellationHandler {
      await withCheckedContinuation { continuation in
        lock.lock()
        if box.resumed {
          lock.unlock()
          continuation.resume(returning: nil)
          return
        }
        if !buffer.isEmpty {
          let element = buffer.removeFirst()
          let producer = producers.first
          if let producer, !producer.value.resumed {
            producers.removeValue(forKey: producer.key)
            producer.value.resumed = true
            lock.unlock()
            producer.value.continuation?.resume(returning: true)
            continuation.resume(returning: element)
            return
          }
          lock.unlock()
          continuation.resume(returning: element)
          return
        }
        if closed {
          lock.unlock()
          continuation.resume(returning: nil)
          return
        }
        if let previous = consumer, !previous.resumed {
          previous.resumed = true
          previous.continuation?.resume(returning: nil)
        }
        box.continuation = continuation
        consumer = box
        lock.unlock()
      }
    } onCancel: {
      lock.lock()
      guard !box.resumed else {
        lock.unlock()
        return
      }
      box.resumed = true
      if consumer === box { consumer = nil }
      let continuation = box.continuation
      lock.unlock()
      continuation?.resume(returning: nil)
    }
  }

  struct Iterator: AsyncIteratorProtocol {
    let channel: BoundedChannel

    func next() async -> Element? {
      await channel.receive()
    }
  }

  func makeAsyncIterator() -> Iterator {
    Iterator(channel: self)
  }
}
