import Foundation

/// Multicast bridge from Kotlin's `MutableSharedFlow`/`MutableStateFlow` to `AsyncStream`.
/// Each `stream` access registers an independent subscriber; with `replayLatest`, a new subscriber
/// immediately receives the most recent element (StateFlow semantics).
public final class AsyncBroadcast<Element: Sendable>: @unchecked Sendable {
  private let lock = NSLock()
  private var continuations: [UUID: AsyncStream<Element>.Continuation] = [:]
  private var latest: Element?
  private let replayLatest: Bool
  private var finished = false

  public init(replayLatest: Bool = false, latest: Element? = nil) {
    self.replayLatest = replayLatest
    self.latest = latest
  }

  public var stream: AsyncStream<Element> {
    let (stream, continuation) = AsyncStream<Element>.makeStream()
    let id = UUID()
    lock.lock()
    if finished {
      lock.unlock()
      continuation.finish()
      return stream
    }
    continuations[id] = continuation
    let replay = replayLatest ? latest : nil
    lock.unlock()
    if let replay { continuation.yield(replay) }
    continuation.onTermination = { [weak self] _ in
      guard let self else { return }
      self.lock.lock()
      self.continuations[id] = nil
      self.lock.unlock()
    }
    return stream
  }

  public var latestSnapshot: Element? {
    lock.withLock { latest }
  }

  public func yield(_ element: Element) {
    lock.lock()
    guard !finished else {
      lock.unlock()
      return
    }
    latest = element
    let targets = Array(continuations.values)
    lock.unlock()
    for target in targets { target.yield(element) }
  }

  public func finish() {
    lock.lock()
    finished = true
    let targets = Array(continuations.values)
    continuations.removeAll()
    lock.unlock()
    for target in targets { target.finish() }
  }
}
