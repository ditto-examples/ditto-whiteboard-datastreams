import Foundation
import Testing
@testable import WhiteboardCore

private struct PresenceFailure: Error, Equatable {
  var message: String
}

@Suite("RetryingPresenceFlowTest")
struct RetryingPresenceFlowTest {
  @Test func upstreamFailureResubscribesAndDeliversTheNextGraph() async throws {
    let collections = LockedBox(0)
    let failures = LockedBox<[String]>([])

    let value: String = try await withRetryingBackoff(
      onFailure: { error in
        failures.withLock {
          $0.append((error as? PresenceFailure)?.message ?? String(describing: error))
        }
      },
      delayMillis: { _ in 0 }
    ) {
      let attempt = collections.withLock { count -> Int in
        count += 1
        return count
      }
      if attempt == 1 { throw PresenceFailure(message: "SDK presence failed") }
      return "recovered"
    }

    #expect(value == "recovered")
    #expect(failures.value == ["SDK presence failed"])
  }

  @Test func graphProcessingFailureIsRecoverableWhenPlacedUpstreamOfRetry() async throws {
    let processingAttempts = LockedBox(0)

    let value: String = try await withRetryingBackoff(delayMillis: { _ in 0 }) {
      let attempt = processingAttempts.withLock { count -> Int in
        count += 1
        return count
      }
      if attempt == 1 { throw PresenceFailure(message: "collector failed") }
      return "graph"
    }

    #expect(value == "graph")
    #expect(processingAttempts.value == 2)
  }

  @Test func backoffDoublesThenShiftsNoMoreThanFourTimes() {
    let delays = (0..<8).map { presenceRetryDelayMillis(attempt: Int64($0)) }
    #expect(delays == [250, 500, 1_000, 2_000, 4_000, 4_000, 4_000, 4_000])
  }

  @Test func cancellationStopsRetrying() async {
    let attempts = LockedBox(0)
    let task = Task {
      try await withRetryingBackoff(delayMillis: { _ in 10_000 }) { () -> String in
        attempts.withLock { $0 += 1 }
        throw PresenceFailure(message: "always failing")
      }
    }
    #expect(await eventually { attempts.value == 1 })
    task.cancel()
    do {
      let _: String = try await task.value
      Issue.record("expected cancellation to propagate")
    } catch is CancellationError {
    } catch {
      Issue.record("expected CancellationError, got \(error)")
    }
  }
}
