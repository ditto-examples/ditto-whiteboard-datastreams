import Foundation

/// Capped shifting backoff for presence retries: 250ms doubling per attempt, shifting at most 4
/// times (4000ms), capped at 5000ms — matching `RetryingPresenceFlow.kt`.
public func presenceRetryDelayMillis(attempt: Int64) -> Int64 {
  min(250 << min(attempt, 4), 5_000)
}

/// Presence is a process-lifetime signal. Recover from an SDK failure or unexpected completion
/// instead of silently ending discovery forever. Re-runs `operation` after each non-cancellation
/// failure, sleeping `delayMillis(attempt)` between attempts; rethrows `CancellationError`.
///
/// Unlike the Kotlin flow operator, a normal return is a success (the callback-based Swift shape
/// has no "completed without emitting" signal to retry on).
@discardableResult
public func withRetryingBackoff<T: Sendable>(
  onFailure: @Sendable (any Error) -> Void = { _ in },
  delayMillis: @Sendable (Int64) -> Int64 = presenceRetryDelayMillis(attempt:),
  operation: @Sendable () async throws -> T
) async throws -> T {
  var attempt: Int64 = 0
  while true {
    do {
      return try await operation()
    } catch is CancellationError {
      throw CancellationError()
    } catch {
      onFailure(error)
      try await Task.sleep(nanoseconds: UInt64(max(0, delayMillis(attempt))) * 1_000_000)
      attempt += 1
    }
  }
}
