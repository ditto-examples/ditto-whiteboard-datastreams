package com.ditto.whiteboard.transport

/** Pure policy shared by presence, stream registration, and Hello handling. */
internal fun shouldGatePeerForInitialSync(
  peer: String,
  synchronizedPeers: Set<String>,
  waivedPeers: Set<String>,
): Boolean = peer !in synchronizedPeers && peer !in waivedPeers

/**
 * Atomically safe admission pattern for concurrent readiness sets.
 *
 * The second check closes the window where a successful Hello can synchronize/waive a peer after
 * the first check but before the awaiting insertion. Completion also removes the awaiting entry,
 * so either interleaving leaves the peer ungated.
 */
internal fun tryAwaitInitialSync(
  peer: String,
  synchronizedPeers: Set<String>,
  waivedPeers: Set<String>,
  awaitingPeers: MutableSet<String>,
  afterInitialCheck: () -> Unit = {},
): Boolean {
  if (!shouldGatePeerForInitialSync(peer, synchronizedPeers, waivedPeers)) return false
  afterInitialCheck()
  val added = awaitingPeers.add(peer)
  if (!shouldGatePeerForInitialSync(peer, synchronizedPeers, waivedPeers)) {
    awaitingPeers -= peer
    return false
  }
  return added
}

/** A live timeout is an absolute gate deadline and must not be restarted by progress or churn. */
internal fun shouldStartInitialSyncDeadline(
  ready: Boolean,
  initialReadinessResolved: Boolean,
  timeoutActive: Boolean,
): Boolean = ready && !initialReadinessResolved && !timeoutActive

internal fun hasOutstandingInitialSync(
  visiblePeers: Set<String>,
  awaitingPeers: Set<String>,
  waivedPeers: Set<String>,
): Boolean = (awaitingPeers + waivedPeers).any { it in visiblePeers }

internal fun connectivityMessageAfterNewInitialSyncGate(
  currentMessage: String?,
  newGateStarted: Boolean,
): String? = if (
  newGateStarted &&
  currentMessage?.startsWith("Initial nearby sync timed out") == true
) {
  null
} else {
  currentMessage
}
