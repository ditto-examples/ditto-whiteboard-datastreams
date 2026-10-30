package com.ditto.whiteboard.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PeerPingTest {
  @Test fun requestMatchesRustAndSwiftWireFormat() {
    val request = PeerPing.request(0x0102030405060708)
    assertEquals(64, request.size)
    assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), request.copyOfRange(0, 8))
    assertTrue(request.drop(8).all { it == 0.toByte() })
  }

  @Test fun matchingEchoProducesRtt() = runTest {
    assertTrue(PeerPing.measure(3) { it } >= 0)
  }

  @Test fun corruptionInSequenceOrPaddingRejectsSample() = runTest {
    for (offset in listOf(0, 7, 63)) {
      try {
        PeerPing.measure(3) { it.copyOf().apply { this[offset] = (this[offset].toInt() xor 1).toByte() } }
        fail("Corrupt echo must not produce RTT")
      } catch (error: PeerPingException) { assertEquals(PeerPingFailure.UnexpectedEcho, error.failure) }
    }
  }

  @Test fun truncatedEchoRejectsSample() = runTest {
    try {
      PeerPing.measure(0) { it.copyOf(8) }
      fail("Truncated echo must not produce RTT")
    } catch (error: PeerPingException) { assertEquals(PeerPingFailure.UnexpectedEcho, error.failure) }
  }

  @Test fun transportFailurePropagates() = runTest {
    try {
      PeerPing.measure(0) { throw PeerPingException(PeerPingFailure.TimedOut) }
      fail("Timeout must not produce RTT")
    } catch (error: PeerPingException) { assertEquals(PeerPingFailure.TimedOut, error.failure) }
  }

  @Test fun cancellationPropagates() = runTest {
    try {
      PeerPing.measure(0) { throw CancellationException() }
      fail("Cancellation must propagate")
    } catch (_: CancellationException) { }
  }
  @Test fun responderEchoesWholePayloadAndEndsOnClosure() = runTest {
    val payload = PeerPing.request(4)
    val incoming = ArrayDeque(listOf(payload))
    var echoed: ByteArray? = null
    PeerPing.respond(receive = { incoming.removeFirstOrNull() }, send = { echoed = it })
    assertArrayEquals(payload, echoed)
  }

  @Test fun responderContainsSendFailure() = runTest {
    var attempts = 0
    PeerPing.respond(receive = { PeerPing.request(0) }, send = { attempts++; error("Disconnected") })
    assertEquals(1, attempts)
  }

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  @Test fun silentResponderSessionExpires() = runTest {
    PeerPing.respond(receive = { awaitCancellation() }, send = { fail("No request to echo") })
    assertEquals(15_000L, currentTime)
  }

  @Test fun responderRejectsOversizeAndHonorsMessageBudget() = runTest {
    PeerPing.respond(receive = { ByteArray(1_025) }, send = { fail("Oversized input") })
    var echoes = 0
    PeerPing.respond(receive = { PeerPing.request(0) }, send = { echoes++ })
    assertEquals(100, echoes)
  }

  @Test fun responderCancellationPropagates() = runTest {
    try {
      PeerPing.respond(receive = { throw CancellationException() }, send = { fail("Cancelled") })
      fail("Cancellation must propagate")
    } catch (_: CancellationException) { }
  }

}
