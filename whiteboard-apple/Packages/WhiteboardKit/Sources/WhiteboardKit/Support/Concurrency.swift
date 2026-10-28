import Foundation

/// Async mutual exclusion, mirroring `Mutex.withLock` from kotlinx.coroutines: acquisition
/// suspends without cancelling, and the critical section may itself suspend.
final class AsyncMutex: @unchecked Sendable {
  private final class Waiter {
    var resumed = false
    var continuation: CheckedContinuation<Void, Never>?
  }

  private let lock = NSLock()
  private var held = false
  private var waiters: [Waiter] = []

  func withLock<T: Sendable>(_ body: () async throws -> T) async rethrows -> T {
    await acquire()
    defer { release() }
    return try await body()
  }

  private func acquire() async {
    let box = Waiter()
    await withCheckedContinuation { continuation in
      lock.lock()
      if !held {
        held = true
        lock.unlock()
        continuation.resume()
        return
      }
      box.continuation = continuation
      waiters.append(box)
      lock.unlock()
    }
  }

  private func release() {
    lock.lock()
    while let next = waiters.first {
      waiters.removeFirst()
      if next.resumed { continue }
      next.resumed = true
      let continuation = next.continuation
      lock.unlock()
      continuation?.resume()
      return
    }
    held = false
    lock.unlock()
  }
}

/// `withTimeoutOrNull`: `nil` when the deadline elapsed first. Cancellation propagates.
func withTimeout<T: Sendable>(
  milliseconds: Int64,
  _ body: @escaping @Sendable () async throws -> T
) async throws -> T? {
  try await withThrowingTaskGroup(of: T?.self) { group in
    group.addTask { try await body() }
    group.addTask {
      try await Task.sleep(nanoseconds: UInt64(max(0, milliseconds)) * 1_000_000)
      return nil
    }
    defer { group.cancelAll() }
    return try await group.next()!
  }
}

/// DittoSwiftTests run `DittoSync.start()` on a dedicated large-stack thread: with NGN enabled it
/// synchronously starts the UDP transport, whose spawned future occupies several hundred KB of
/// stack in debug builds — enough to overflow the cooperative-thread stacks async tasks run on.
func runOnLargeStack<T: Sendable>(_ body: @escaping @Sendable () throws -> T) async throws -> T {
  try await withCheckedThrowingContinuation { continuation in
    let thread = Thread {
      continuation.resume(with: Result { try body() })
    }
    thread.stackSize = 64 << 20
    thread.start()
  }
}

func uptimeNanos() -> UInt64 {
  DispatchTime.now().uptimeNanoseconds
}

func currentTimeMillis() -> Int64 {
  Int64(Date().timeIntervalSince1970 * 1_000)
}

func sleep(milliseconds: Int64) async {
  try? await Task.sleep(nanoseconds: UInt64(max(0, milliseconds)) * 1_000_000)
}

/// Task inventory cancelled wholesale on teardown; every task the transport spawns is registered
/// so `close()` matches the Kotlin `SupervisorJob.cancel()` sweep.
final class TaskRegistry: @unchecked Sendable {
  private let lock = NSLock()
  private var tasks: [UUID: Task<Void, Never>] = [:]
  private var cancelled = false

  func spawn(_ body: @escaping @Sendable () async -> Void) -> Task<Void, Never> {
    let id = UUID()
    let task = Task { [self] in
      guard !Task.isCancelled else {
        lock.withLock { _ = tasks.removeValue(forKey: id) }
        return
      }
      await body()
      lock.withLock { _ = tasks.removeValue(forKey: id) }
    }
    lock.lock()
    if cancelled {
      lock.unlock()
      task.cancel()
      return task
    }
    tasks[id] = task
    lock.unlock()
    return task
  }

  func cancelAll() {
    let all: [Task<Void, Never>] = lock.withLock {
      cancelled = true
      let values = Array(tasks.values)
      tasks.removeAll()
      return values
    }
    for task in all { task.cancel() }
  }
}
