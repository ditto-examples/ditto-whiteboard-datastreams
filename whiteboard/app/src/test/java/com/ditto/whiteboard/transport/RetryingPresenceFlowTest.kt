package com.ditto.whiteboard.transport

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RetryingPresenceFlowTest {
  @Test
  fun upstreamFailureResubscribesAndDeliversTheNextGraph() = runTest {
    var collections = 0
    val failures = mutableListOf<String?>()
    val value = flow {
      collections += 1
      if (collections == 1) error("SDK presence failed")
      emit("recovered")
    }.retryPresenceFailures(
      onFailure = { failures += it.message },
      delayMillis = { 0L },
    ).first()

    assertEquals("recovered", value)
    assertEquals(listOf("SDK presence failed"), failures)
  }

  @Test
  fun graphProcessingFailureIsRecoverableWhenPlacedUpstreamOfRetry() = runTest {
    var processingAttempts = 0
    val value = flowOf("graph")
      .onEach {
        processingAttempts += 1
        if (processingAttempts == 1) error("collector failed")
      }
      .retryPresenceFailures(onFailure = {}, delayMillis = { 0L })
      .first()

    assertEquals("graph", value)
    assertEquals(2, processingAttempts)
  }
}
