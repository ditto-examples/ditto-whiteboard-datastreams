public func shouldGatePeerForInitialSync(
  peer: String,
  synchronizedPeers: Set<String>,
  waivedPeers: Set<String>
) -> Bool {
  !synchronizedPeers.contains(peer) && !waivedPeers.contains(peer)
}

// The peer sets are read through closures so the second check observes mutations performed by
// afterInitialCheck, mirroring the Kotlin callers that share mutable sets across the call.
public func tryAwaitInitialSync(
  peer: String,
  synchronizedPeers: () -> Set<String>,
  waivedPeers: () -> Set<String>,
  awaitingPeers: inout Set<String>,
  afterInitialCheck: () -> Void = {}
) -> Bool {
  if !shouldGatePeerForInitialSync(
    peer: peer, synchronizedPeers: synchronizedPeers(), waivedPeers: waivedPeers()
  ) {
    return false
  }
  afterInitialCheck()
  let added = awaitingPeers.insert(peer).inserted
  if !shouldGatePeerForInitialSync(
    peer: peer, synchronizedPeers: synchronizedPeers(), waivedPeers: waivedPeers()
  ) {
    awaitingPeers.remove(peer)
    return false
  }
  return added
}

public func shouldStartInitialSyncDeadline(
  ready: Bool,
  initialReadinessResolved: Bool,
  timeoutActive: Bool
) -> Bool {
  ready && !initialReadinessResolved && !timeoutActive
}

public func hasOutstandingInitialSync(
  visiblePeers: Set<String>,
  awaitingPeers: Set<String>,
  waivedPeers: Set<String>
) -> Bool {
  awaitingPeers.union(waivedPeers).contains { visiblePeers.contains($0) }
}

public func connectivityMessageAfterNewInitialSyncGate(
  currentMessage: String?,
  newGateStarted: Bool
) -> String? {
  if newGateStarted && currentMessage?.hasPrefix("Initial nearby sync timed out") == true {
    return nil
  }
  return currentMessage
}
