package com.ditto.whiteboard.transport

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.retryWhen

/**
 * Presence is a process-lifetime signal. Recover from an SDK failure or unexpected normal
 * completion instead of silently ending discovery forever.
 */
internal fun <T> Flow<T>.retryPresenceFailures(
  onFailure: (Throwable) -> Unit,
  delayMillis: (attempt: Long) -> Long = { attempt ->
    (250L shl attempt.coerceAtMost(4).toInt()).coerceAtMost(5_000L)
  },
): Flow<T> =
  onCompletion { cause ->
    if (cause == null) error("Presence observation completed unexpectedly")
  }.retryWhen { cause, attempt ->
    if (cause is CancellationException) throw cause
    if (cause !is Exception) throw cause
    onFailure(cause)
    delay(delayMillis(attempt))
    true
  }
