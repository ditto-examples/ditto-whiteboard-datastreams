package com.ditto.whiteboard.util

import kotlin.coroutines.cancellation.CancellationException

/** Captures ordinary synchronous failures while preserving cancellation and fatal JVM errors. */
internal inline fun <T> runCatchingException(block: () -> T): Result<T> = try {
  Result.success(block())
} catch (cancelled: CancellationException) {
  throw cancelled
} catch (error: Exception) {
  Result.failure(error)
}

/** Captures ordinary suspend failures while preserving cancellation and fatal JVM errors. */
internal suspend fun <T> runSuspendCatchingPreservingCancellation(
  block: suspend () -> T,
): Result<T> = try {
  Result.success(block())
} catch (cancelled: CancellationException) {
  throw cancelled
} catch (error: Exception) {
  Result.failure(error)
}
