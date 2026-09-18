package com.ditto.whiteboard.transport

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Bounded, serialized application lanes keyed by peer.
 *
 * A slow BoardSession acknowledgement from one peer cannot occupy the global reliable decoder or
 * delay another peer's Hello/snapshot traffic, while operations from the same peer remain ordered.
 */
internal class PerPeerOperationDispatcher<T>(
  private val scope: CoroutineScope,
  private val capacityPerPeer: Int,
  private val handle: suspend (peerKey: String, value: T) -> Unit,
) : AutoCloseable {
  private data class Lane<T>(val channel: Channel<T>, val job: Job)

  private val lanes = ConcurrentHashMap<String, Lane<T>>()

  fun tryDispatch(peerKey: String, value: T): Boolean {
    val lane = lanes.computeIfAbsent(peerKey) { key ->
      val channel = Channel<T>(capacityPerPeer)
      val job = scope.launch {
        for (item in channel) handle(key, item)
      }
      Lane(channel, job)
    }
    return lane.channel.trySend(value).isSuccess
  }

  fun remove(peerKey: String) {
    lanes.remove(peerKey)?.let { lane ->
      lane.channel.close()
      lane.job.cancel()
    }
  }

  fun retainPeers(peerKeys: Set<String>) {
    lanes.keys.filterNot { it in peerKeys }.forEach(::remove)
  }

  override fun close() {
    lanes.keys.toList().forEach(::remove)
  }
}
