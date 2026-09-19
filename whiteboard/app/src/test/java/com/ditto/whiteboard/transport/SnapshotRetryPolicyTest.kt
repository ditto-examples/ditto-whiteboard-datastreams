package com.ditto.whiteboard.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotRetryPolicyTest {
  @Test
  fun retryLadderBacksOffExponentiallyFromOneSecond() {
    assertEquals(1_000L, snapshotRetryDelayMillis(1))
    assertEquals(2_000L, snapshotRetryDelayMillis(2))
    assertEquals(4_000L, snapshotRetryDelayMillis(3))
  }

  @Test(expected = IllegalArgumentException::class)
  fun retryLadderRejectsNonPositiveAttempts() {
    snapshotRetryDelayMillis(0)
  }

  @Test
  fun budgetAllowsExactlyTheConfiguredNumberOfRetriesThenGivesUp() {
    val budget = SnapshotRetryBudget()
    val granted = generateSequence { budget.consumeAttempt("peer") }.take(MAX_SNAPSHOT_RETRIES).toList()

    assertEquals((1..MAX_SNAPSHOT_RETRIES).toList(), granted)
    assertNull("budget must be exhausted after $MAX_SNAPSHOT_RETRIES retries", budget.consumeAttempt("peer"))
  }

  /**
   * The regression this replaces: the attempt counter was incremented before the caller checked
   * whether a retry was already in flight, so skipped retries silently spent the budget. Counting
   * now happens only via [SnapshotRetryBudget.consumeAttempt], whose result the caller must act on.
   */
  @Test
  fun budgetIsNotSpentByPeersThatNeverRetried() {
    val budget = SnapshotRetryBudget()
    repeat(MAX_SNAPSHOT_RETRIES) { assertNotNull(budget.consumeAttempt("noisy")) }
    assertNull(budget.consumeAttempt("noisy"))

    assertEquals(1, budget.consumeAttempt("quiet"))
  }

  @Test
  fun resetRestoresTheFullBudgetForOnePeerOnly() {
    val budget = SnapshotRetryBudget()
    repeat(MAX_SNAPSHOT_RETRIES) { budget.consumeAttempt("a") }
    repeat(MAX_SNAPSHOT_RETRIES) { budget.consumeAttempt("b") }

    budget.reset("a")

    assertEquals(1, budget.consumeAttempt("a"))
    assertNull(budget.consumeAttempt("b"))
  }

  @Test
  fun retainPeersDropsDepartedPeersBudgets() {
    val budget = SnapshotRetryBudget()
    repeat(MAX_SNAPSHOT_RETRIES) { budget.consumeAttempt("gone") }
    repeat(MAX_SNAPSHOT_RETRIES) { budget.consumeAttempt("present") }

    budget.retainPeers(setOf("present"))

    assertEquals("a departed peer rejoins with a fresh budget", 1, budget.consumeAttempt("gone"))
    assertNull(budget.consumeAttempt("present"))
  }

  /**
   * The bound is per holder, not in total. A 10-peer late join hands the slot between up to nine
   * transfers in sequence; a fixed total deadline (the earlier design) would expire on that
   * perfectly healthy mesh, which is exactly the false assurance the old constant-comparison test
   * failed to catch.
   */
  @Test
  fun waitingSurvivesTheSlotBeingHandedBetweenEveryPeerInTurn() {
    val wait = HydrationSlotWait()
    var keptWaiting = true

    // Nine holders, each occupying the slot for a full transfer lifetime, one after another.
    repeat(MAX_CONNECTED_PEERS - 1) { holder ->
      var elapsed = 0L
      while (elapsed < MAX_SNAPSHOT_TRANSFER_LIFETIME_MILLIS) {
        keptWaiting = keptWaiting && wait.keepWaiting("peer-$holder/transfer-$holder", HYDRATION_SLOT_POLL_MILLIS)
        elapsed += HYDRATION_SLOT_POLL_MILLIS
      }
    }

    assertTrue(
      "a waiter must stay patient for the whole serial hand-off, far beyond one transfer",
      keptWaiting,
    )
  }

  @Test
  fun aSingleHolderThatOverstaysOneTransferLifetimeEndsTheWait() {
    val wait = HydrationSlotWait()
    var elapsed = 0L
    while (wait.keepWaiting("stuck-peer/stuck-transfer", HYDRATION_SLOT_POLL_MILLIS)) {
      elapsed += HYDRATION_SLOT_POLL_MILLIS
      assertTrue("the wait must terminate, not run forever", elapsed <= MAX_HYDRATION_SLOT_WAIT_MILLIS * 2)
    }

    assertTrue(
      "giving up only after a full transfer lifetime, not before",
      elapsed >= MAX_SNAPSHOT_TRANSFER_LIFETIME_MILLIS,
    )
  }

  @Test
  fun aHolderChangeRestartsTheBudgetEvenForTheSamePeerRetransmitting() {
    val wait = HydrationSlotWait()
    repeat(10) { wait.keepWaiting("peer-a/transfer-1", HYDRATION_SLOT_POLL_MILLIS) }
    assertTrue(wait.waitedOnCurrentHolderMillis > 0)

    // Same peer, new transfer id: that is progress, not a wedge.
    wait.keepWaiting("peer-a/transfer-2", HYDRATION_SLOT_POLL_MILLIS)

    assertEquals(0L, wait.waitedOnCurrentHolderMillis)
  }

  @Test(expected = IllegalArgumentException::class)
  fun slotWaitRejectsNegativeElapsedTime() {
    HydrationSlotWait().keepWaiting("peer/transfer", -1L)
  }

  /**
   * The BLOCKER this replaces: the sender collapsed every `accepted = false` into the error ladder,
   * so it exhausted three strikes in ~7s and reconnected while the receiver was patiently waiting
   * for its hydration slot — churn, for the entire duration of the transfer it was queued behind.
   */
  @Test
  fun aBusyRefusalIsBackpressureAndNeverConsumesTheErrorBudget() {
    assertEquals(
      SnapshotAckOutcome.Backpressure,
      snapshotAckOutcome(accepted = false, busy = true),
    )
  }

  @Test
  fun aPlainRefusalIsStillAFailure() {
    assertEquals(SnapshotAckOutcome.Failed, snapshotAckOutcome(accepted = false, busy = false))
  }

  @Test
  fun anAcceptedAckIsAcceptedRegardlessOfTheBusyFlag() {
    assertEquals(SnapshotAckOutcome.Accepted, snapshotAckOutcome(accepted = true, busy = false))
    assertEquals(SnapshotAckOutcome.Accepted, snapshotAckOutcome(accepted = true, busy = true))
  }

  /** Backpressure must be the *only* outcome that bypasses the ladder. */
  @Test
  fun onlyBackpressureBypassesTheRetryLadder() {
    val budget = SnapshotRetryBudget()
    listOf(
      snapshotAckOutcome(accepted = false, busy = false),
      snapshotAckOutcome(accepted = false, busy = false),
      snapshotAckOutcome(accepted = false, busy = false),
    ).forEach { outcome ->
      assertEquals(SnapshotAckOutcome.Failed, outcome)
      assertNotNull(budget.consumeAttempt("peer"))
    }
    assertNull("three genuine failures exhaust the budget", budget.consumeAttempt("peer"))
  }
}
