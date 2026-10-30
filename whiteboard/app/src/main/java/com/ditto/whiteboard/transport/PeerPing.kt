package com.ditto.whiteboard.transport

import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Wire-compatible with the Rust Data Streams ping example; never changes board state. */
object PeerPing {
  const val TOPIC = "ping"
  const val COUNT = 5
  const val PAYLOAD_SIZE = 64
  const val MAX_PAYLOAD_SIZE = 1_024

  /** Stop a diagnostic responder on idle, malformed input or send failure; never crash its owner. */
  internal suspend fun respond(receive: suspend () -> ByteArray?, send: suspend (ByteArray) -> Unit) {
    try {
      repeat(100) {
        val echoed = withTimeoutOrNull(15_000) {
          val bytes = receive() ?: return@withTimeoutOrNull false
          if (bytes.size !in 8..MAX_PAYLOAD_SIZE) return@withTimeoutOrNull false
          send(bytes)
          true
        } ?: false
        if (!echoed) return
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      // A disconnect can race a send. End this diagnostic session, not the app.
    }
  }

  fun request(sequence: Long): ByteArray = ByteBuffer.allocate(PAYLOAD_SIZE).putLong(sequence).array()

  suspend fun measure(sequence: Long, exchange: suspend (ByteArray) -> ByteArray): Double {
    val request = request(sequence)
    val started = System.nanoTime()
    val response = exchange(request)
    currentCoroutineContext().ensureActive()
    if (!response.contentEquals(request)) throw PeerPingException(PeerPingFailure.UnexpectedEcho)
    return (System.nanoTime() - started) / 1_000_000.0
  }
}

enum class PeerPingFailure { Unavailable, Busy, ConnectionFailed, TimedOut, Closed, UnexpectedEcho, TooLarge }
class PeerPingException(val failure: PeerPingFailure) : Exception(failure.name)
