package com.ditto.whiteboard.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PerPeerOperationDispatcherTest {
  @Test
  fun stalledPeerDoesNotBlockAnotherPeerAndEachLaneStaysOrdered() = runTest {
    val releaseFirstPeer = CompletableDeferred<Unit>()
    val handled = mutableListOf<String>()
    val dispatcher = PerPeerOperationDispatcher<String>(
      scope = backgroundScope,
      capacityPerPeer = 2,
    ) { peer, value ->
      if (peer == "slow" && value == "one") releaseFirstPeer.await()
      handled += "$peer:$value"
    }

    assertTrue(dispatcher.tryDispatch("slow", "one"))
    assertTrue(dispatcher.tryDispatch("slow", "two"))
    assertTrue(dispatcher.tryDispatch("healthy", "hello"))
    runCurrent()

    assertEquals(listOf("healthy:hello"), handled)
    releaseFirstPeer.complete(Unit)
    runCurrent()
    assertEquals(listOf("healthy:hello", "slow:one", "slow:two"), handled)
  }
}
