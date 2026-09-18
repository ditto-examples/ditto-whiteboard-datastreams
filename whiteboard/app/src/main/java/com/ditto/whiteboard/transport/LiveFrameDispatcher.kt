package com.ditto.whiteboard.transport

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * Drains lossy live frames without letting one closed recipient permanently kill the shared
 * dispatcher. Cancellation still propagates so transport shutdown remains prompt.
 */
internal suspend fun <Frame, Recipient> dispatchLiveFrames(
  frames: ReceiveChannel<Frame>,
  encode: (Frame) -> ByteArray,
  recipients: () -> Collection<Recipient>,
  maxSendSize: (Recipient) -> Long,
  send: (Recipient, ByteArray) -> Unit,
  onSent: (Recipient) -> Unit,
  onFailure: (Recipient?, Throwable) -> Unit,
  throttle: suspend () -> Unit,
) {
  for (frame in frames) {
    try {
      val bytes = encode(frame)
      recipients().forEach { recipient ->
        try {
          if (bytes.size.toLong() <= maxSendSize(recipient)) {
            send(recipient, bytes)
            onSent(recipient)
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          onFailure(recipient, error)
        }
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      onFailure(null, error)
    }
    throttle()
  }
}
