@file:OptIn(com.ditto.kotlin.PreviewDataStreams::class)

package com.ditto.whiteboard.transport

import com.ditto.kotlin.Ditto
import com.ditto.kotlin.DittoAcceptor
import com.ditto.kotlin.DittoReliability
import com.ditto.kotlin.DittoStream
import com.ditto.kotlin.open
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex

/** A separate reliable diagnostic topic, owned by the nearby networking lifecycle. */
internal class PeerPingService(private val ditto: Ditto, private val scope: CoroutineScope) {
  private val lock = Any()
  private var generation = 0
  private var running = false
  private var acceptor: DittoAcceptor? = null
  private val sessions = mutableSetOf<PingConnection>()
  private val clientLock = Mutex()

  fun start() {
    val epoch = synchronized(lock) { generation }
    if (synchronized(lock) { running }) return
    val binding = ditto.dataStreams.bindTopic(PeerPing.TOPIC, DittoReliability.Reliable) { candidate ->
      val connection = try { reserve() } catch (_: PeerPingException) { return@bindTopic }
      try {
        val stream = candidate.open { inbound -> connection.receive(inbound.payload()) }
        if (!connection.attach(stream)) { release(connection); return@bindTopic }
        val job = scope.launch(Dispatchers.IO) {
          val monitor = launch {
            try { stream.waitUntilClosed() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* The stream may already be closed by local teardown. */ }
            finally { connection.close() }
          }
          try {
            PeerPing.respond(
              receive = { connection.messages.receiveCatching().getOrNull() },
              send = { payload ->
                if (payload.size.toLong() > stream.maxSendSize()) throw PeerPingException(PeerPingFailure.TooLarge)
                stream.send(payload)
              },
            )
          } finally { monitor.cancel(); release(connection) }
        }
        job.invokeOnCompletion { release(connection) }
      } catch (_: Exception) { release(connection) }
    }
    val installed = synchronized(lock) {
      if (generation != epoch) false else {
        acceptor = binding
        running = true
        true
      }
    }
    if (!installed) { binding.close(); throw PeerPingException(PeerPingFailure.Unavailable) }
  }

  fun stop() {
    val resources = synchronized(lock) {
      running = false
      generation += 1
      val resources = acceptor to sessions.toList()
      acceptor = null
      sessions.clear()
      resources
    }
    resources.first?.close()
    resources.second.forEach { it.close() }
  }

  suspend fun ping(peerKey: String, onSample: (Double) -> Unit) = coroutineScope {
    if (!clientLock.tryLock()) throw PeerPingException(PeerPingFailure.Busy)
    try {
      val connection = reserve()
      try {
        val stream = try {
          ditto.dataStreams.connect(
            peer = peerKey, topic = PeerPing.TOPIC, reliability = DittoReliability.Reliable,
            requestCompression = false, timeoutMs = 5_000,
          ) { candidate -> candidate.open { inbound -> connection.receive(inbound.payload()) } }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          currentCoroutineContext().ensureActive()
          throw PeerPingException(PeerPingFailure.ConnectionFailed)
        }
        if (!connection.attach(stream)) throw PeerPingException(PeerPingFailure.Closed)
        val monitor = launch {
          try { stream.waitUntilClosed() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* The stream may already be closed by local teardown. */ }
            finally { connection.messages.close() }
        }
        try {
          if (PeerPing.PAYLOAD_SIZE.toLong() > stream.maxSendSize()) throw PeerPingException(PeerPingFailure.TooLarge)
          repeat(PeerPing.COUNT) { sequence ->
            currentCoroutineContext().ensureActive()
            val rtt = PeerPing.measure(sequence.toLong()) { request ->
              stream.sendAndForget(request)
              withTimeoutOrNull(5_000) {
                connection.messages.receiveCatching().getOrNull()
                  ?: throw PeerPingException(PeerPingFailure.Closed)
              } ?: throw PeerPingException(PeerPingFailure.TimedOut)
            }
            onSample(rtt)
          }
        } finally { monitor.cancel() }
      } finally { release(connection) }
    } finally { clientLock.unlock() }
  }

  private fun reserve(): PingConnection = synchronized(lock) {
    if (!running) throw PeerPingException(PeerPingFailure.Unavailable)
    if (sessions.size >= 11) throw PeerPingException(PeerPingFailure.Busy)
    PingConnection().also { sessions += it }
  }

  private fun release(connection: PingConnection) {
    connection.close()
    synchronized(lock) { sessions -= connection }
  }
}

private class PingConnection {
  val messages = Channel<ByteArray>(1)
  private var stream: DittoStream? = null
  private var closed = false

  fun receive(bytes: ByteArray) {
    if (bytes.size !in 8..PeerPing.MAX_PAYLOAD_SIZE || !messages.trySend(bytes).isSuccess) close()
  }

  fun attach(value: DittoStream): Boolean {
    val accepted = synchronized(this) {
      if (closed) false else { stream = value; true }
    }
    if (!accepted) value.close()
    return accepted
  }

  fun close() {
    val value = synchronized(this) {
      closed = true
      stream.also { stream = null }
    }
    messages.close()
    value?.close()
  }
}
