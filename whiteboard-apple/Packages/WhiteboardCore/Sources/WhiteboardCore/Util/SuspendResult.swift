import Foundation

// Port of util/SuspendResult.kt: capture ordinary failures while rethrowing cancellation.
func runCatchingPreservingCancellation<T>(
  _ block: @Sendable () throws -> T
) throws(CancellationError) -> Result<T, any Error> {
  do {
    return .success(try block())
  } catch is CancellationError {
    throw CancellationError()
  } catch {
    return .failure(error)
  }
}

func runAsyncCatchingPreservingCancellation<T>(
  _ block: @Sendable () async throws -> T
) async throws(CancellationError) -> Result<T, any Error> {
  do {
    return .success(try await block())
  } catch is CancellationError {
    throw CancellationError()
  } catch {
    return .failure(error)
  }
}
