package com.ditto.whiteboard.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InitialReadinessPolicyTest {
  @Test
  fun digestMismatchAfterTimeoutWaiverDoesNotRelockEditing() {
    assertFalse(
      shouldGatePeerForInitialSync(
        peer = "peer",
        synchronizedPeers = emptySet(),
        waivedPeers = setOf("peer"),
      ),
    )
  }

  @Test
  fun onlyAnUnresolvedUnwaivedPeerGatesEditing() {
    assertTrue(shouldGatePeerForInitialSync("peer", emptySet(), emptySet()))
    assertFalse(shouldGatePeerForInitialSync("peer", setOf("peer"), emptySet()))
  }

  @Test
  fun equalDigestCompletedBeforeReadinessDelayIsNotRegated() {
    val awaiting = mutableSetOf<String>()
    assertFalse(
      tryAwaitInitialSync(
        peer = "peer",
        synchronizedPeers = setOf("peer"),
        waivedPeers = emptySet(),
        awaitingPeers = awaiting,
      ),
    )
    assertTrue(awaiting.isEmpty())
  }

  @Test
  fun completionBetweenCandidateCheckAndAwaitingInsertCannotRegatePeer() {
    val synchronized = mutableSetOf<String>()
    val awaiting = mutableSetOf<String>()

    assertFalse(
      tryAwaitInitialSync(
        peer = "peer",
        synchronizedPeers = synchronized,
        waivedPeers = emptySet(),
        awaitingPeers = awaiting,
        afterInitialCheck = { synchronized += "peer" },
      ),
    )
    assertTrue(awaiting.isEmpty())
  }

  @Test
  fun activeAbsoluteDeadlineIsNotRestartedByMoreTrafficOrPeers() {
    assertTrue(
      shouldStartInitialSyncDeadline(
        ready = true,
        initialReadinessResolved = false,
        timeoutActive = false,
      ),
    )
    assertFalse(
      shouldStartInitialSyncDeadline(
        ready = true,
        initialReadinessResolved = false,
        timeoutActive = true,
      ),
    )
  }

  @Test
  fun timeoutWarningCanClearAfterWaivedPeerBecomesIncompatible() {
    assertTrue(
      hasOutstandingInitialSync(
        visiblePeers = setOf("peer"),
        awaitingPeers = emptySet(),
        waivedPeers = setOf("peer"),
      ),
    )
    assertFalse(
      hasOutstandingInitialSync(
        visiblePeers = setOf("peer"),
        awaitingPeers = emptySet(),
        waivedPeers = emptySet(),
      ),
    )
  }

  @Test
  fun newPeerGateClearsPriorWaiverMessageEvenWhileOldPeerRemainsVisible() {
    val timedOut = "Initial nearby sync timed out. Editing is available."

    assertNull(connectivityMessageAfterNewInitialSyncGate(timedOut, newGateStarted = true))
    assertEquals(timedOut, connectivityMessageAfterNewInitialSyncGate(timedOut, newGateStarted = false))
    assertEquals(
      "Nearby presence failed",
      connectivityMessageAfterNewInitialSyncGate("Nearby presence failed", newGateStarted = true),
    )
  }
}
