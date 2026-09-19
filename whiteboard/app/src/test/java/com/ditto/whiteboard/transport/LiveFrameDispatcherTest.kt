package com.ditto.whiteboard.transport

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveFrameDispatcherTest {
  @Test
  fun closedRecipientDoesNotKillDispatcherOrBlockHealthyRecipient() = runTest {
    val frames = Channel<Int>(Channel.UNLIMITED)
    frames.send(1)
    frames.send(2)
    frames.close()
    val sent = mutableListOf<Pair<String, Int>>()
    val failures = mutableListOf<String?>()

    dispatchLiveFrames(
      frames = frames,
      encode = { byteArrayOf(it.toByte()) },
      recipients = { listOf("closed", "healthy") },
      maxSendSize = { recipient ->
        if (recipient == "closed") error("stream closed")
        64L
      },
      send = { recipient, bytes -> sent += recipient to bytes.single().toInt() },
      onSent = {},
      onFailure = { recipient, _ -> failures += recipient },
      throttle = {},
    )

    assertEquals(listOf("healthy" to 1, "healthy" to 2), sent)
    assertEquals(listOf("closed", "closed"), failures)
  }
}
