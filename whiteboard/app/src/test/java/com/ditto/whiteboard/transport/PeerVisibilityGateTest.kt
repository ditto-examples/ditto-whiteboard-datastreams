package com.ditto.whiteboard.transport

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerVisibilityGateTest {
  @Test
  fun departureCleanupCannotBeOvertakenByLateResourceCreation() {
    val gate = PeerVisibilityGate()
    val resources = mutableSetOf<String>()
    gate.update(setOf("peer")) {}
    gate.enable()
    val creationEntered = CountDownLatch(1)
    val releaseCreation = CountDownLatch(1)
    val executor = Executors.newFixedThreadPool(2)
    try {
      val creation = executor.submit<String?> {
        gate.ifVisible("peer") {
          creationEntered.countDown()
          assertTrue(releaseCreation.await(2, TimeUnit.SECONDS))
          resources += "peer"
          "created"
        }
      }
      assertTrue(creationEntered.await(2, TimeUnit.SECONDS))
      val removal = executor.submit {
        gate.update(emptySet()) { removed -> resources.removeAll(removed) }
      }
      releaseCreation.countDown()

      assertEquals("created", creation.get(2, TimeUnit.SECONDS))
      removal.get(2, TimeUnit.SECONDS)
      assertTrue(resources.isEmpty())
      assertNull(gate.ifVisible("peer") { "recreated" })
    } finally {
      executor.shutdownNow()
    }
  }

  @Test
  fun pausedGateCannotRecreateResourcesForStillVisiblePeer() {
    val gate = PeerVisibilityGate()
    val resources = mutableSetOf<String>()
    gate.update(setOf("peer")) {}
    gate.enable()
    assertEquals("created", gate.ifVisible("peer") {
      resources += "peer"
      "created"
    })

    gate.disable { resources.clear() }

    assertNull(gate.ifVisible("peer") {
      resources += "peer"
      "recreated"
    })
    assertTrue(resources.isEmpty())
  }
}
