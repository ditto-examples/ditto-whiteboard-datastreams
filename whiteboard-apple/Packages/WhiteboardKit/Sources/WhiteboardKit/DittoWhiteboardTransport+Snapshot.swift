import Foundation
import WhiteboardCore
@_spi(PreviewDataStreams) import DittoSwift

extension DittoWhiteboardTransport {
  // MARK: - Hello coalescing

  func handleHello(peer: String, envelope: Ditto_Whiteboard_V4_Envelope) {
    withState { $0.pendingHellos[peer] = envelope }
    ensureHelloCoalescer(peer)
  }

  private func ensureHelloCoalescer(_ peer: String) {
    withState { state in
      guard state.helloCoalesceTasks[peer] == nil else { return }
      state.helloCoalesceTasks[peer] = spawn { [self] in
        defer {
          _ = withState { $0.helloCoalesceTasks.removeValue(forKey: peer) }
          // A Hello can arrive between the final empty check and map removal.
          if withState({ $0.pendingHellos[peer] != nil }) { ensureHelloCoalescer(peer) }
        }
        while !Task.isCancelled {
          if let previous = withState({ $0.lastHelloNanos[peer] }) {
            let remainingNanos = Int64(helloMinIntervalNanos) - Int64(uptimeNanos() &- previous)
            if remainingNanos > 0 {
              // Ceiling division prevents an early sub-millisecond wake from orphaning the latest
              // pending Hello without another scheduled coalescer.
              await sleep(milliseconds: (remainingNanos + 999_999) / 1_000_000)
            }
          }
          guard !Task.isCancelled,
                let latest = withState({ $0.pendingHellos.removeValue(forKey: peer) })
          else { break }
          withState { $0.lastHelloNanos[peer] = uptimeNanos() }
          await processHello(peer: peer, envelope: latest)
        }
      }
    }
  }

  private func processHello(peer: String, envelope: Ditto_Whiteboard_V4_Envelope) async {
    if let remoteProfile = try? WhiteboardProtocol.decodeProfile(
      envelope.hello.profileJson,
      expectedPeerKey: peer
    ) {
      updatePeer(peer) { entry in
        entry.displayName = remoteProfile.displayName
        entry.colorArgb = remoteProfile.colorArgb
      }
    }
    let material = stateMaterial()
    let remoteDigest = envelope.hello.stateDigest
    withState { $0.lastRemoteDigests[peer] = remoteDigest }
    let digestMatches = remoteDigest == material.digest
    traceReadiness(
      "hello peer=\(peer.suffix(6)) localOps=\(material.state.operations.count) "
        + "local=\(material.digest.tracePrefix) remote=\(remoteDigest.tracePrefix) match=\(digestMatches)"
    )
    if digestMatches {
      markInitialSyncComplete(peer)
    } else {
      let newGateStarted = withState { state -> Bool in
        let synchronized = state.synchronizedPeerKeys
        let waived = state.readinessWaivedPeerKeys
        let added = tryAwaitInitialSync(
          peer: peer,
          synchronizedPeers: { synchronized },
          waivedPeers: { waived },
          awaitingPeers: &state.awaitingInitialSyncPeerKeys
        )
        if added {
          state.initialReadinessResolved = false
          return true
        }
        return false
      }
      if newGateStarted { refreshEditingReadiness() }
    }
    let snapshotsReady = withState { $0.ready }
    if snapshotsReady,
       shouldOfferSnapshot(local: material.state, remoteDigest: remoteDigest, localDigest: material.digest)
    {
      // Launched off the shared reliable worker so a slow/backed-up peer can't head-of-line block
      // inbound processing for every other peer. Serialized per peer: a second concurrent
      // transfer would interleave frames on the peer's ordered outbox.
      launchSnapshot(peer)
    }
  }

  // MARK: - Snapshot sending

  func launchSnapshot(_ peer: String) {
    withState { state in
      if state.outboundSnapshots[peer] != nil { return }
      if state.snapshotTasks[peer] != nil {
        state.snapshotRequested.insert(peer)
        return
      }
      state.snapshotRequested.remove(peer)
      state.snapshotTasks[peer] = spawn { [self] in
        await runSnapshotTask(peer: peer)
      }
    }
  }

  private func runSnapshotTask(peer: String) async {
    do {
      try await sendSnapshot(peer)
    } catch is CancellationError {
    } catch {
      withState { $0.outboundSnapshots.removeValue(forKey: peer) }?.timeoutTask?.cancel()
      recordError(peer, error)
      recoverStream(peer, WhiteboardProtocol.stateStreamName, "Snapshot send failed; reconnecting")
      scheduleOutboundSnapshotRetry(peer, "Snapshot send failed")
    }
    let relaunch = withState { state -> Bool in
      state.snapshotTasks.removeValue(forKey: peer)
      guard state.snapshotRequested.remove(peer) != nil,
            state.visiblePeers.contains(peer),
            state.outboundSnapshots[peer] == nil
      else { return false }
      return true
    }
    if relaunch { launchSnapshot(peer) }
  }

  func snapshotMaterial() async throws -> SnapshotMaterial {
    try await snapshotMaterialMutex.withLock {
      let capture = knownLog.capture()
      if let cached = cachedSnapshotMaterial, cached.version == capture.version { return cached }
      // Serialization and hashing happen outside the authoritative log lock. Reliable accepts can
      // keep advancing; operations accepted after this immutable capture are sent independently
      // and merged by the receiver behind the snapshot, so a point-in-time snapshot stays correct.
      let bytes = try WhiteboardProtocol.snapshotBytes(capture.state)
      let material = SnapshotMaterial(
        version: capture.version,
        state: capture.state,
        bytes: bytes,
        byteDigest: WhiteboardProtocol.sha256(bytes)
      )
      if knownLog.isCurrent(capture.version) { cachedSnapshotMaterial = material }
      return material
    }
  }

  func stateMaterial() -> KnownStateMaterial {
    knownLog.material()
  }

  func sendHello(_ peer: String) async {
    guard let currentProfile = withState({ $0.profile }) else { return }
    let material = stateMaterial()
    traceReadiness(
      "send hello peer=\(peer.suffix(6)) ops=\(material.state.operations.count) "
        + "digest=\(material.digest.tracePrefix)"
    )
    guard let bytes = try? WhiteboardProtocol.helloEnvelope(
      peerKey: localPeerKey,
      sequence: nextControlSequence(),
      lamport: material.state.latestStamp?.lamport ?? 0,
      profile: currentProfile,
      state: material.state,
      stateDigest: material.digest
    ) else { return }
    await enqueueOutbox(peer, bytes)
  }

  func sendSnapshot(_ peer: String) async throws {
    let material = try await snapshotMaterial()
    let bytes = material.bytes
    let stateKey = StreamKey(peerKey: peer, topic: WhiteboardProtocol.stateStreamName)
    let rawMax = withState { $0.streams[stateKey] }.flatMap { try? $0.maxSendSize } ?? 49_152
    let streamMaxData = min(max(rawMax - 1_024, 1_024), 48_128)
    // Fit the transfer inside the protocol's chunk-count limit even when the link's per-send
    // budget is too small for that: an oversized chunk envelope is transparently re-chunked into
    // reliable frames by `sendFramed` and reassembled on the receiving side.
    let maxData = max(
      streamMaxData,
      (bytes.count + maxTransferChunks - 1) / maxTransferChunks
    )
    let chunkCount = (bytes.count + maxData - 1) / maxData
    guard (1...maxTransferChunks).contains(chunkCount) else {
      throw WhiteboardCoreError.requirementFailed("Snapshot exceeds the protocol chunk limit")
    }
    // A transfer ID identifies one attempt, not its content. The digest is already carried
    // separately; reusing it here lets an old negative ACK cancel a newer retry of identical bytes.
    let transferId = UUID().uuidString.lowercased()
    let outbound = OutboundSnapshot(transferId: transferId)
    withState { state in
      // Cancel any superseded attempt's 5-minute timeout task instead of leaking it.
      state.outboundSnapshots.updateValue(outbound, forKey: peer)?.timeoutTask?.cancel()
      outbound.timeoutTask = spawn { [self] in
        await sleep(milliseconds: maxSnapshotTransferLifetimeMillis)
        guard !Task.isCancelled else { return }
        let removed = withState { state -> Bool in
          guard state.outboundSnapshots[peer] === outbound else { return false }
          state.outboundSnapshots.removeValue(forKey: peer)
          return true
        }
        if removed {
          recoverStream(peer, WhiteboardProtocol.stateStreamName, "Snapshot acknowledgement timed out")
        }
      }
    }
    updatePeer(peer) { entry in
      entry.snapshotStatus = .sending
      entry.snapshotProgress = 0
    }
    var begin = Ditto_Whiteboard_V4_SnapshotBegin()
    begin.transferID = transferId
    begin.chunkCount = UInt32(chunkCount)
    begin.byteCount = UInt64(bytes.count)
    begin.sha256 = material.byteDigest
    var beginEnvelope = baseEnvelope()
    beginEnvelope.snapshotBegin = begin
    try await sendDirect(peer, try beginEnvelope.serializedBytes() as Data)
    for index in 0..<chunkCount {
      let offset = index * maxData
      let byteCount = min(maxData, bytes.count - offset)
      var chunk = Ditto_Whiteboard_V4_SnapshotChunk()
      chunk.transferID = transferId
      chunk.index = UInt32(index)
      chunk.data = bytes.subdata(in: offset..<(offset + byteCount))
      var envelope = baseEnvelope()
      envelope.snapshotChunk = chunk
      try await sendDirect(peer, try envelope.serializedBytes() as Data)
      updatePeer(peer) { $0.snapshotProgress = Float(index + 1) / Float(chunkCount) }
    }
    var end = Ditto_Whiteboard_V4_SnapshotEnd()
    end.transferID = transferId
    var endEnvelope = baseEnvelope()
    endEnvelope.snapshotEnd = end
    try await sendDirect(peer, try endEnvelope.serializedBytes() as Data)
  }

  private func sendDirect(_ peer: String, _ bytes: Data) async throws {
    let key = StreamKey(peerKey: peer, topic: WhiteboardProtocol.stateStreamName)
    guard let stream = withState({ $0.streams[key] }) else {
      throw WhiteboardCoreError.requirementFailed("State stream is not connected")
    }
    try await peerSendMutex(peer).withLock {
      guard withState({ $0.streams[key] }) === stream else {
        throw WhiteboardCoreError.requirementFailed("State stream changed before send")
      }
      try await sendFramed(peer: peer, stream: stream, payload: bytes)
    }
  }

  /// `busy` marks a refusal caused only by the occupied hydration slot. The sender must treat that
  /// as backpressure rather than spending its error budget and reconnecting.
  func sendSnapshotAck(
    peer: String,
    transferId: String,
    accepted: Bool,
    error: String,
    busy: Bool = false
  ) async {
    var ack = Ditto_Whiteboard_V4_SnapshotAck()
    ack.transferID = transferId
    ack.accepted = accepted
    ack.error = error
    ack.busy = busy
    var envelope = baseEnvelope()
    envelope.snapshotAck = ack
    guard let bytes: Data = try? envelope.serializedBytes() else { return }
    await enqueueOutbox(peer, bytes)
  }

  // MARK: - Snapshot receiving (hydration)

  /// Installs an inbound snapshot transfer. Runs on the shared reliable-ingress worker, so every
  /// step here is non-blocking: acknowledgements and superseded-transfer rejections are handed to
  /// `dispatchSnapshotControl` instead of being awaited inline.
  func beginHydration(peer: String, envelope: Ditto_Whiteboard_V4_Envelope) {
    let begin = envelope.snapshotBegin
    if let reason = invalidTransferHeader(
      transferId: begin.transferID,
      chunkCount: Int(begin.chunkCount),
      byteCount: Int64(begin.byteCount),
      digest: begin.sha256
    ) {
      recordError(peer, reason)
      let ackTransferId = String(begin.transferID.prefix(maxTransferIdLength))
      dispatchSnapshotControl(peer: peer, reason: reason) { [self] in
        await sendSnapshotAck(peer: peer, transferId: ackTransferId, accepted: false, error: reason)
      }
      scheduleSnapshotRetry(peer, reason, resendOutbound: false)
      return
    }
    // The header was validated above, so assembly construction cannot fail.
    guard let assembler = try? SnapshotAssembler(
      transferId: begin.transferID,
      chunkCount: Int(begin.chunkCount),
      byteCount: Int64(begin.byteCount),
      expectedDigest: begin.sha256
    ) else { return }
    let hydration = SnapshotHydration(
      transferId: begin.transferID,
      assembler: assembler,
      chunkCount: Int(begin.chunkCount)
    )
    var busy = false
    let previous = withState { state -> SnapshotHydration? in
      if state.hydrations.keys.contains(where: { $0 != peer }) {
        busy = true
        return nil
      }
      let old = state.hydrations.updateValue(hydration, forKey: peer)
      old?.timeoutTask?.cancel()
      return old
    }
    if busy {
      let reason = "Another snapshot is already being received"
      dispatchSnapshotControl(peer: peer, reason: reason) { [self] in
        await sendSnapshotAck(peer: peer, transferId: begin.transferID, accepted: false, error: reason, busy: true)
      }
      // Not a failure — the single hydration slot is occupied. Waiting for the slot instead of
      // burning the (seconds-long) error retry budget against a transfer that may legitimately
      // take minutes is what keeps a full-mesh late join from collapsing into reconnect churn.
      scheduleHydrationSlotRetry(peer)
      return
    }
    if let previous {
      dispatchSnapshotControl(peer: peer, reason: "Snapshot was superseded by a newer transfer") { [self] in
        try await rejectRemovedHydration(
          peer: peer,
          hydration: previous,
          reason: "Snapshot was superseded by a newer transfer",
          retry: false
        )
      }
    }
    resetHydrationTimeout(peer: peer, hydration: hydration)
    updatePeer(peer) { entry in
      entry.snapshotStatus = .receiving
      entry.snapshotProgress = 0
    }
  }

  /// Hands long-running snapshot control work to a bounded, per-peer lane. Saturation closes the
  /// peer's stream so the reconnect/Hello path reconciles, rather than blocking the caller.
  func dispatchSnapshotControl(
    peer: String,
    reason: String,
    work: @escaping @Sendable () async throws -> Void
  ) {
    if !snapshotControlDispatcher.tryDispatch(peer, work) {
      recoverStream(peer, WhiteboardProtocol.stateStreamName, "Snapshot control queue saturated: \(reason)")
    }
  }

  func handleSnapshotChunk(peer: String, chunk: Ditto_Whiteboard_V4_SnapshotChunk) {
    var rejected: (SnapshotHydration, String)?
    var progress: Float?
    let hydration = withState { state -> SnapshotHydration? in
      guard let current = state.hydrations[peer] else { return nil }
      guard chunk.transferID == current.transferId else { return nil }
      guard !current.finishing else { return nil }
      switch current.assembler.add(index: Int(chunk.index), bytes: chunk.data) {
      case .rejected(let reason):
        if state.hydrations[peer] === current {
          state.hydrations.removeValue(forKey: peer)
          current.timeoutTask?.cancel()
          rejected = (current, reason)
        }
      default:
        current.receivedChunks.insert(Int(chunk.index))
        progress = Float(current.receivedChunks.count) / Float(current.chunkCount)
      }
      return current
    }
    guard let hydration else { return }
    if let (rejectedHydration, reason) = rejected {
      dispatchSnapshotControl(peer: peer, reason: reason) { [self] in
        try await rejectRemovedHydration(peer: peer, hydration: rejectedHydration, reason: reason)
      }
      return
    }
    resetHydrationTimeout(peer: peer, hydration: hydration)
    if let progress {
      updatePeer(peer) { $0.snapshotProgress = progress }
    }
  }

  /// Snapshot parsing and the session's prepare/commit/apply handshake must not occupy the one
  /// reliable-ingress worker. Keep this hydration installed while it finalizes so operations that
  /// follow SnapshotEnd are still bounded and replayed after the snapshot is committed.
  func launchHydrationFinish(peer: String, transferId: String) {
    let hydration = withState { state -> SnapshotHydration? in
      guard let current = state.hydrations[peer], current.transferId == transferId else { return nil }
      guard !current.finishing, current.finishTask == nil else { return nil }
      current.finishing = true
      current.timeoutTask?.cancel()
      return current
    }
    guard let hydration else { return }
    let task = spawn { [self] in
      do {
        let finished = try await withTimeout(milliseconds: snapshotFinishTimeoutMillis) {
          try await self.finishHydration(peer: peer, hydration: hydration)
          return true
        } ?? false
        if !finished {
          // The transfer-phase timeout was cancelled when the final chunk arrived; without this
          // bound a stalled session handshake would hold the single hydration slot forever.
          try? await rejectHydration(
            peer: peer,
            hydration: hydration,
            reason: "Snapshot finalization timed out",
            fromFinishTask: true
          )
        }
      } catch is CancellationError {
      } catch {
        recordError(peer, error)
        try? await rejectHydration(
          peer: peer,
          hydration: hydration,
          reason: error.localizedDescription,
          fromFinishTask: true
        )
      }
    }
    let discard = withState { state -> Bool in
      guard state.hydrations[peer] === hydration, hydration.finishTask == nil else { return true }
      hydration.finishTask = task
      return false
    }
    if discard { task.cancel() }
  }

  private func finishHydration(peer: String, hydration: SnapshotHydration) async throws {
    let transferId = hydration.transferId
    switch hydration.assembler.finish(transferId) {
    case .complete(let bytes):
      let snapshotOperations: [BoardOperation]
      do {
        snapshotOperations = try WhiteboardProtocol.snapshotOperations(bytes)
      } catch {
        try await rejectHydration(
          peer: peer,
          hydration: hydration,
          reason: error.localizedDescription,
          fromFinishTask: true
        )
        return
      }
      if snapshotOperations.contains(where: { $0.stamp.lamport > WhiteboardProtocol.maxSessionLamport }) {
        try await rejectHydration(
          peer: peer,
          hydration: hydration,
          reason: "Snapshot exceeds the board logical-time limit",
          fromFinishTask: true
        )
        return
      }
      guard withState({ $0.hydrations[peer] === hydration }) else { return }
      let initiallyBuffered = hydration.bufferedOperations
      let mergedOperations = snapshotOperations + initiallyBuffered.values
      var remoteMaterial: [OperationId: BoardOperation] = [:]
      for operation in mergedOperations {
        remoteMaterial[operation.id] = remoteMaterial[operation.id]
          .map { canonicalOperation($0, operation) } ?? operation
      }
      let remoteMaterialDigest = WhiteboardProtocol.operationSetDigest(remoteMaterial.values)
      let prepared = HandshakeSignal()
      let committed = HandshakeSignal()
      let applied = HandshakeSignal()
      defer {
        prepared.complete(false)
        committed.complete(false)
        applied.complete(false)
      }
      eventsBroadcast.yield(
        .snapshotMerged(
          operations: mergedOperations,
          prepared: prepared,
          committed: committed,
          applied: applied
        )
      )
      guard try await awaitHandshake(prepared) else {
        committed.complete(false)
        try await rejectHydration(
          peer: peer,
          hydration: hydration,
          reason: "Snapshot could not be applied by the board session",
          fromFinishTask: true
        )
        return
      }
      if let mergeError = knownLog.merge(mergedOperations) {
        committed.complete(false)
        try await rejectHydration(peer: peer, hydration: hydration, reason: mergeError, fromFinishTask: true)
        return
      }
      committed.complete(true)
      guard try await awaitHandshake(applied) else {
        try await rejectHydration(
          peer: peer,
          hydration: hydration,
          reason: "Snapshot apply acknowledgement timed out",
          fromFinishTask: true
        )
        return
      }
      let trailingOperations = withState { state -> [BoardOperation]? in
        guard state.hydrations[peer] === hydration else { return nil }
        state.hydrations.removeValue(forKey: peer)
        hydration.timeoutTask?.cancel()
        return hydration.bufferedOperations
          .filter { id, operation in initiallyBuffered[id] != operation }
          .map(\.value)
      }
      guard let trailingOperations else { return }
      let mergedMaterial = stateMaterial()
      let reciprocalSnapshotNeeded = shouldSendReciprocalSnapshot(
        receivedMaterialDigest: remoteMaterialDigest,
        mergedLocalDigest: mergedMaterial.digest
      )
      updatePeer(peer) { entry in
        entry.snapshotStatus = .merged
        entry.snapshotProgress = 1
      }
      traceReadiness(
        "snapshot merged peer=\(peer.suffix(6)) received=\(snapshotOperations.count) "
          + "known=\(stateMaterial().state.operations.count) digest=\(stateMaterial().digest.tracePrefix)"
      )
      clearInboundSnapshotRetry(peer)
      await sendSnapshotAck(peer: peer, transferId: transferId, accepted: true, error: "")
      markInitialSyncComplete(peer)
      if reciprocalSnapshotNeeded { requestReciprocalSnapshot(peer) }
      for operation in trailingOperations {
        try await deliverOperation(peer: peer, operation: operation)
      }
      // Advertise the merged digest immediately. This coalesces a full-mesh late join: other peers
      // that were rejected while the one global hydration slot was occupied see equality before
      // retrying and do not send duplicate 16 MiB snapshots.
      let connectedPeers = withState { state in
        Set(
          state.streams.keys
            .filter { $0.topic == WhiteboardProtocol.stateStreamName }
            .map(\.peerKey)
        )
      }
      for connectedPeer in connectedPeers { await sendHello(connectedPeer) }
    case .rejected(let reason):
      try await rejectHydration(peer: peer, hydration: hydration, reason: reason, fromFinishTask: true)
    case .pending:
      try await rejectHydration(
        peer: peer,
        hydration: hydration,
        reason: "Snapshot ended before all chunks arrived",
        fromFinishTask: true
      )
    }
  }

  func rejectHydration(
    peer: String,
    hydration: SnapshotHydration,
    reason: String,
    retry: Bool = true,
    fromFinishTask: Bool = false
  ) async throws {
    let removed = withState { state -> Bool in
      guard state.hydrations[peer] === hydration else { return false }
      state.hydrations.removeValue(forKey: peer)
      hydration.timeoutTask?.cancel()
      if !fromFinishTask { hydration.finishTask?.cancel() }
      return true
    }
    guard removed else { return }
    try await rejectRemovedHydration(
      peer: peer,
      hydration: hydration,
      reason: reason,
      retry: retry,
      fromFinishTask: fromFinishTask
    )
  }

  func rejectRemovedHydration(
    peer: String,
    hydration: SnapshotHydration,
    reason: String,
    retry: Bool = true,
    fromFinishTask: Bool = false
  ) async throws {
    if !fromFinishTask { hydration.finishTask?.cancel() }
    // Operations received after SnapshotBegin are independently valid reliable messages. Re-apply
    // them after rejecting the snapshot instead of silently discarding them with the transfer.
    for operation in hydration.bufferedOperations.values {
      try await deliverOperation(peer: peer, operation: operation)
    }
    // A superseded transfer is rejected asynchronously, by which time its replacement may already
    // be receiving. Never let the stale rejection overwrite the live transfer's progress.
    let superseded = withState { $0.hydrations[peer] != nil }
    if superseded {
      recordError(peer, reason)
    } else {
      updatePeer(peer) { entry in
        entry.snapshotStatus = .rejected
        entry.lastError = reason
      }
    }
    await sendSnapshotAck(
      peer: peer,
      transferId: hydration.transferId,
      accepted: false,
      error: String(reason.prefix(256))
    )
    if retry { scheduleSnapshotRetry(peer, reason, resendOutbound: false) }
  }

  /// Treats the timeout as inactivity, resetting on every valid chunk, while retaining a hard
  /// five-minute lifetime so a peer cannot pin a 16 MiB assembly forever by trickling duplicates.
  func resetHydrationTimeout(peer: String, hydration: SnapshotHydration) {
    withState { state in
      guard state.hydrations[peer] === hydration else { return }
      hydration.timeoutTask?.cancel()
      let ageMillis = Int64((uptimeNanos() - hydration.startedAtNanos) / 1_000_000)
      let remainingLifetime = maxSnapshotTransferLifetimeMillis - ageMillis
      if remainingLifetime <= 0 {
        hydration.timeoutTask = spawn { [self] in
          try? await rejectHydration(
            peer: peer,
            hydration: hydration,
            reason: "Snapshot exceeded its maximum transfer lifetime"
          )
        }
        return
      }
      hydration.timeoutTask = spawn { [self] in
        await sleep(milliseconds: min(transferInactivityTimeoutMillis, remainingLifetime))
        guard !Task.isCancelled else { return }
        try? await rejectHydration(peer: peer, hydration: hydration, reason: "Snapshot transfer timed out")
      }
    }
  }

  // MARK: - Snapshot acknowledgement and retries

  func handleSnapshotAck(peer: String, ack: Ditto_Whiteboard_V4_SnapshotAck) {
    if ack.transferID.isBlankTransport || ack.transferID.utf16.count > maxTransferIdLength {
      recordError(peer, "Invalid snapshot acknowledgement")
      return
    }
    let outbound = withState { $0.outboundSnapshots[peer] }
    guard let outbound, outbound.transferId == ack.transferID else {
      recordError(peer, "Snapshot acknowledgement does not match the active transfer")
      return
    }
    if ack.accepted {
      traceReadiness("snapshot acknowledged peer=\(peer.suffix(6)) id=\(ack.transferID.prefix(8))")
      withState { state in
        if state.outboundSnapshots[peer] === outbound {
          state.outboundSnapshots.removeValue(forKey: peer)
        }
      }
      outbound.timeoutTask?.cancel()
      clearOutboundSnapshotRetry(peer)
      updatePeer(peer) { entry in
        entry.snapshotStatus = .acknowledged
        entry.snapshotProgress = 1
      }
    } else {
      let reason = safeDiagnostic(ack.error.isBlankTransport ? "Remote peer rejected the snapshot" : ack.error)
      withState { state in
        if state.outboundSnapshots[peer] === outbound {
          state.outboundSnapshots.removeValue(forKey: peer)
        }
      }
      outbound.timeoutTask?.cancel()
      // Stop enqueueing chunks after an immediate BEGIN rejection. The outbound retry has its own
      // direction-specific state and will start only after this producer has unwound.
      withState { $0.snapshotTasks.removeValue(forKey: peer) }?.cancel()
      if snapshotAckOutcome(accepted: ack.accepted, busy: ack.busy) == .backpressure {
        // Backpressure, not failure. The receiver is waiting for its hydration slot and will send
        // a fresh Hello once it frees, which re-drives the offer; keep a long fallback only in
        // case that Hello is lost.
        updatePeer(peer) { entry in
          entry.snapshotStatus = .queued
          entry.snapshotProgress = 0
        }
        scheduleQueuedSnapshotResend(peer)
      } else {
        updatePeer(peer) { entry in
          entry.snapshotStatus = .rejected
          entry.lastError = reason
        }
        scheduleOutboundSnapshotRetry(peer, reason)
      }
    }
  }

  func scheduleSnapshotRetry(_ peer: String, _ reason: String, resendOutbound: Bool) {
    var exhausted = false
    withState { state in
      guard state.visiblePeers.contains(peer) else { return }
      // The attempt is consumed while holding the state lock so a retry skipped because one is
      // already in flight cannot silently consume the budget and trip the limit.
      guard state.snapshotRetryTasks[peer] == nil else { return }
      guard let attempt = state.snapshotRetryAttempts.consumeAttempt(peer) else {
        exhausted = true
        return
      }
      let delay = (try? snapshotRetryDelayMillis(attempt: attempt)) ?? snapshotRetryBaseDelayMillis
      state.snapshotRetryTasks[peer] = spawn { [self] in
        defer { withState { $0.snapshotRetryTasks.removeValue(forKey: peer) } }
        await sleep(milliseconds: delay)
        guard !Task.isCancelled else { return }
        if resendOutbound {
          launchSnapshot(peer)
        } else {
          await sendHello(peer)
        }
      }
    }
    if exhausted {
      recordError(peer, "Snapshot retry limit reached: \(reason)")
      withState { state in
        state.snapshotRetryAttempts.reset(peer)
        state.outboundSnapshots.removeValue(forKey: peer)?.timeoutTask?.cancel()
      }
      recoverStream(peer, WhiteboardProtocol.stateStreamName, "Snapshot retry limit reached; reconnecting")
    }
  }

  /// Waits for the single inbound hydration slot instead of retrying against a fixed budget. A
  /// 16 MiB snapshot can take minutes on a BLE link, so the seconds-long error ladder used by
  /// `scheduleSnapshotRetry` would exhaust itself long before the slot frees and drop every
  /// waiting peer into a reconnect loop.
  func scheduleHydrationSlotRetry(_ peer: String) {
    withState { state in
      guard state.visiblePeers.contains(peer) else { return }
      guard state.hydrationSlotWaitTasks[peer] == nil else { return }
      state.hydrationSlotWaitTasks[peer] = spawn { [self] in
        defer { withState { $0.hydrationSlotWaitTasks.removeValue(forKey: peer) } }
        var wait = HydrationSlotWait()
        var slotFreed = true
        while true {
          guard let holder = currentHydrationHolder(excluding: peer) else { break }
          let keepWaiting = (try? wait.keepWaiting(
            currentHolder: holder,
            elapsedMillis: hydrationSlotPollMillis
          )) ?? false
          guard keepWaiting else {
            slotFreed = false
            break
          }
          await sleep(milliseconds: hydrationSlotPollMillis)
          guard !Task.isCancelled, withState({ $0.visiblePeers.contains(peer) }) else { return }
        }
        guard withState({ $0.visiblePeers.contains(peer) }) else { return }
        if slotFreed {
          // Re-advertise the digest: the completed transfer may already have converged us.
          await sendHello(peer)
        } else {
          // Waiting is not a failure, so the peer starts its reconnect with a full error budget.
          withState { $0.snapshotRetryAttempts.reset(peer) }
          recoverStream(
            peer,
            WhiteboardProtocol.stateStreamName,
            "A peer held the snapshot slot beyond one transfer lifetime"
          )
        }
      }
    }
  }

  /// Re-offers a snapshot the remote refused as busy, once, after a long delay. Gated on the
  /// peer's last advertised digest: if the pair converged while queued, the multi-megabyte no-op
  /// transfer is skipped.
  func scheduleQueuedSnapshotResend(_ peer: String) {
    withState { state in
      guard state.visiblePeers.contains(peer) else { return }
      guard state.queuedSnapshotTasks[peer] == nil else { return }
      state.queuedSnapshotTasks[peer] = spawn { [self] in
        defer { withState { $0.queuedSnapshotTasks.removeValue(forKey: peer) } }
        await sleep(milliseconds: maxHydrationSlotWaitMillis)
        guard !Task.isCancelled, withState({ $0.visiblePeers.contains(peer) }) else { return }
        let material = stateMaterial()
        let remoteDigest = withState { $0.lastRemoteDigests[peer] }
        let stillDiverged = remoteDigest == nil
          || shouldOfferSnapshot(local: material.state, remoteDigest: remoteDigest!, localDigest: material.digest)
        if stillDiverged { launchSnapshot(peer) }
      }
    }
  }

  func scheduleOutboundSnapshotRetry(_ peer: String, _ reason: String) {
    var exhausted = false
    withState { state in
      guard state.visiblePeers.contains(peer) else { return }
      guard state.outboundSnapshotRetryTasks[peer] == nil else { return }
      guard let attempt = state.outboundSnapshotRetryAttempts.consumeAttempt(peer) else {
        exhausted = true
        return
      }
      let delay = (try? snapshotRetryDelayMillis(attempt: attempt)) ?? snapshotRetryBaseDelayMillis
      state.outboundSnapshotRetryTasks[peer] = spawn { [self] in
        defer { withState { $0.outboundSnapshotRetryTasks.removeValue(forKey: peer) } }
        await sleep(milliseconds: delay)
        guard !Task.isCancelled else { return }
        launchSnapshot(peer)
      }
    }
    if exhausted {
      recordError(peer, "Snapshot resend limit reached: \(reason)")
      withState { $0.outboundSnapshotRetryAttempts.reset(peer) }
      recoverStream(peer, WhiteboardProtocol.stateStreamName, "Snapshot resend limit reached; reconnecting")
    }
  }

  /// Identifies whoever currently occupies the single hydration slot, ignoring `excluding`. The
  /// transfer id is part of the identity so a same-peer retransmit counts as progress.
  private func currentHydrationHolder(excluding: String) -> String? {
    withState { state in
      state.hydrations
        .first { $0.key != excluding }
        .map { "\($0.key)/\($0.value.transferId)" }
    }
  }

  func clearSnapshotRetry(_ peer: String) {
    clearInboundSnapshotRetry(peer)
    clearOutboundSnapshotRetry(peer)
  }

  func clearInboundSnapshotRetry(_ peer: String) {
    withState { state in
      state.snapshotRetryTasks.removeValue(forKey: peer)?.cancel()
      state.hydrationSlotWaitTasks.removeValue(forKey: peer)?.cancel()
      state.snapshotRetryAttempts.reset(peer)
    }
  }

  func clearOutboundSnapshotRetry(_ peer: String) {
    withState { state in
      state.outboundSnapshotRetryTasks.removeValue(forKey: peer)?.cancel()
      state.queuedSnapshotTasks.removeValue(forKey: peer)?.cancel()
      state.outboundSnapshotRetryAttempts.reset(peer)
    }
  }

  /// A received merge-only snapshot can be a strict subset of local history. Send the merged union
  /// back once any older outbound transfer has been acknowledged, so convergence does not depend
  /// on a one-shot Hello being delivered in both directions.
  func requestReciprocalSnapshot(_ peer: String) {
    withState { state in
      guard state.visiblePeers.contains(peer) else { return }
      guard state.reciprocalSnapshotTasks[peer] == nil else { return }
      state.reciprocalSnapshotTasks[peer] = spawn { [self] in
        defer { withState { $0.reciprocalSnapshotTasks.removeValue(forKey: peer) } }
        let available: Bool
        do {
          available = try await withTimeout(milliseconds: transferInactivityTimeoutMillis) { [self] in
            while withState({ $0.outboundSnapshots[peer] != nil || $0.snapshotTasks[peer] != nil }) {
              if Task.isCancelled { throw CancellationError() }
              await sleep(milliseconds: 50)
            }
            return true
          } ?? false
        } catch is CancellationError {
          return
        } catch {
          return
        }
        let visible = withState { $0.visiblePeers.contains(peer) }
        if available && visible {
          launchSnapshot(peer)
        } else if visible {
          recoverStream(peer, WhiteboardProtocol.stateStreamName, "Reciprocal snapshot could not start")
        }
      }
    }
  }
}
