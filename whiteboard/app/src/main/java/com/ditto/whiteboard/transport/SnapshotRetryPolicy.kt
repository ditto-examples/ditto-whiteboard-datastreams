package com.ditto.whiteboard.transport

/** Give up on a snapshot direction after this many *scheduled* retries and reconnect instead. */
internal const val MAX_SNAPSHOT_RETRIES = 3

/** First retry delay; each further attempt doubles it. */
internal const val SNAPSHOT_RETRY_BASE_DELAY_MILLIS = 1_000L

/** How often a peer waiting for the single inbound hydration slot re-checks it. */
internal const val HYDRATION_SLOT_POLL_MILLIS = 250L

/**
 * How long a peer waits on a *single* hydration-slot holder before giving up and reconnecting.
 *
 * Sized to outlast one complete transfer: its hard lifetime cap, the bounded finalization that
 * follows the last chunk (`SNAPSHOT_FINISH_TIMEOUT_MILLIS` — the transfer-phase timeout is
 * cancelled while parsing and the session handshake run), plus one inactivity timeout of slack.
 * It is deliberately NOT a bound on total waiting: with N contenders the slot is handed off
 * serially, so total occupancy is up to (N-1) transfers and any fixed total deadline would expire
 * on a healthy, progressing mesh. [HydrationSlotWait] therefore restarts this budget whenever the
 * holder changes, which turns the bound into "no single transfer may wedge the slot" — a property
 * the per-hydration hard lifetime in `resetHydrationTimeout` and the finalization deadline in
 * `launchHydrationFinish` actually guarantee.
 */
internal const val MAX_HYDRATION_SLOT_WAIT_MILLIS =
  MAX_SNAPSHOT_TRANSFER_LIFETIME_MILLIS + SNAPSHOT_FINISH_TIMEOUT_MILLIS +
    TRANSFER_INACTIVITY_TIMEOUT_MILLIS

/**
 * Decides whether a peer queued behind the single hydration slot should keep waiting.
 *
 * Waiting is bounded per holder, not in total: as long as the slot keeps changing hands the mesh is
 * making progress and the waiter stays patient. Only a holder that overstays a full transfer
 * lifetime — which its own timeout should have prevented — makes the waiter give up and reconnect.
 */
internal class HydrationSlotWait(
  private val maxWaitPerHolderMillis: Long = MAX_HYDRATION_SLOT_WAIT_MILLIS,
) {
  private var holder: String? = null
  private var waitedMillis = 0L

  /** How long the current holder has been observed holding the slot. */
  val waitedOnCurrentHolderMillis: Long get() = waitedMillis

  /**
   * Records one poll against [currentHolder] (a peer key plus transfer id, so a same-peer retry
   * counts as progress). Returns false once this holder has overstayed and the caller should
   * stop waiting.
   */
  fun keepWaiting(currentHolder: String, elapsedMillis: Long): Boolean {
    require(elapsedMillis >= 0) { "Elapsed time cannot be negative" }
    if (currentHolder != holder) {
      holder = currentHolder
      waitedMillis = 0L
    } else {
      waitedMillis += elapsedMillis
    }
    return waitedMillis <= maxWaitPerHolderMillis
  }
}

/**
 * Bounded exponential backoff between snapshot retries, in milliseconds.
 *
 * [attempt] is 1-based. Only genuine protocol failures (bad header, digest mismatch, timeout) use
 * this ladder; contention for the single hydration slot is backpressure, not failure, and waits on
 * the slot instead — see `scheduleHydrationSlotRetry`.
 */
internal fun snapshotRetryDelayMillis(attempt: Int): Long {
  require(attempt >= 1) { "Retry attempts are 1-based" }
  return SNAPSHOT_RETRY_BASE_DELAY_MILLIS shl (attempt - 1)
}

/**
 * Whether a retry should be scheduled for [attempt], or the caller should reconnect instead.
 *
 * Counting an attempt and deciding on it must happen together under the retry-job map's lock:
 * incrementing for a retry that is then skipped (because one is already in flight) would exhaust
 * the budget without ever having retried.
 */
internal fun shouldScheduleSnapshotRetry(attempt: Int): Boolean = attempt <= MAX_SNAPSHOT_RETRIES

/**
 * Per-peer retry budget for one snapshot direction (inbound or outbound).
 *
 * [consumeAttempt] is the only way to count an attempt, and it returns null once the budget is
 * spent, so a caller cannot increment without also acting on the result. Callers invoke it from
 * inside the retry-job map's remapping function, which is what makes "counted" and "scheduled"
 * inseparable — the bug this replaces incremented first and then silently skipped scheduling when
 * a retry was already in flight, tripping the limit after as few as one real retry.
 */
internal class SnapshotRetryBudget {
  private val attempts = java.util.concurrent.ConcurrentHashMap<String, Int>()

  /** Returns the 1-based attempt number, or null when [peer] has no budget left. */
  fun consumeAttempt(peer: String): Int? {
    val attempt = attempts.merge(peer, 1, Int::plus) ?: 1
    return if (shouldScheduleSnapshotRetry(attempt)) attempt else null
  }

  fun reset(peer: String) {
    attempts.remove(peer)
  }

  fun retainPeers(peers: Set<String>) {
    attempts.keys.removeIf { it !in peers }
  }

  fun clear() {
    attempts.clear()
  }
}

/** What a snapshot sender should do with an inbound [com.ditto.whiteboard.protocol.proto.SnapshotAck]. */
internal enum class SnapshotAckOutcome {
  /** The remote merged the snapshot. Clear outbound state. */
  Accepted,

  /**
   * The remote refused only because its single hydration slot is occupied. This is flow control:
   * do not spend the error budget, do not tear down the stream. The remote will re-advertise its
   * digest once the slot frees.
   */
  Backpressure,

  /** A genuine rejection (bad header, digest mismatch, superseded). Use the error retry ladder. */
  Failed,
}

/**
 * Classifies a snapshot acknowledgement.
 *
 * Collapsing [Backpressure] into [Failed] — which is all a sender could do before `SnapshotAck`
 * carried `busy` — makes a contended full-mesh late join exhaust the three-strike ladder in a few
 * seconds and reconnect, repeatedly, while the receiver is still waiting for its slot.
 */
internal fun snapshotAckOutcome(accepted: Boolean, busy: Boolean): SnapshotAckOutcome = when {
  accepted -> SnapshotAckOutcome.Accepted
  busy -> SnapshotAckOutcome.Backpressure
  else -> SnapshotAckOutcome.Failed
}
