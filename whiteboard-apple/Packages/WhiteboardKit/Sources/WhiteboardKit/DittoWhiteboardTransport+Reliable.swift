import Foundation
import WhiteboardCore
@_spi(PreviewDataStreams) import DittoSwift

extension DittoWhiteboardTransport {
  // MARK: - Reliable outbox

  /// Enqueues a reliable frame for `peer`, applying backpressure via the bounded outbox. Never
  /// resurrects an outbox for a peer that has already left the mesh, and tolerates a concurrently
  /// closed channel, so producers can't wedge on a departed peer.
  func enqueueOutbox(_ peer: String, _ bytes: Data) async {
    guard let channel = outbox(peer) else { return }
    await channel.send(bytes)
  }

  func tryEnqueueOutbox(_ peer: String, _ bytes: Data) -> Bool {
    outbox(peer)?.trySend(bytes) ?? false
  }

  func outbox(_ peer: String) -> BoundedChannel<Data>? {
    outboxVisibility.ifVisible(peer) { [self] in
      withState { state in
        if let existing = state.outboxes[peer] { return existing }
        // Bounded so a stalled peer applies backpressure to its producers instead of growing an
        // unbounded queue. The SDK itself offers no backpressure, so this is the app's safety valve.
        let channel = BoundedChannel<Data>(capacity: reliableOutboxCapacity)
        state.outboxes[peer] = channel
        state.outboxTasks[peer] = spawn { [self] in
          await outboxDrain(peer: peer, channel: channel)
        }
        return channel
      }
    }
  }

  private func outboxDrain(peer: String, channel: BoundedChannel<Data>) async {
    let stateKey = StreamKey(peerKey: peer, topic: WhiteboardProtocol.stateStreamName)
    for await payload in channel {
      var sent = false
      var attempts = 0
      var streamlessWaits = 0
      while !Task.isCancelled && !sent {
        let stream = withState { $0.streams[stateKey] }
        guard let stream else {
          // No stream yet. Wait for a reconnect, but stop waiting once the peer has left so the
          // drain can never block the channel (and its producers) indefinitely. Also bound the
          // wait for a peer that stays visible but never gets a stream: otherwise the drain sits
          // here forever, the channel wedges at capacity, and the reliable fan-out to every other
          // peer stalls behind it. Drop the frame after the bound; it re-heals via snapshot.
          if !withState({ $0.visiblePeers.contains(peer) }) { break }
          streamlessWaits += 1
          if streamlessWaits > maxStreamlessWaits { break }
          await sleep(milliseconds: 100)
          continue
        }
        streamlessWaits = 0
        do {
          try await peerSendMutex(peer).withLock {
            try await sendFramed(peer: peer, stream: stream, payload: payload)
          }
          sent = true
        } catch is CancellationError {
          return
        } catch {
          recordError(peer, error)
          withState { state in
            if state.streams[stateKey] === stream {
              state.streams.removeValue(forKey: stateKey)
            }
          }
          stream.close()
          // Give up on this frame after repeated failures so it can't spin forever and hold the
          // channel full. Operations are idempotent and the snapshot path re-heals state.
          attempts += 1
          if attempts >= maxSendAttempts { break }
          await sleep(milliseconds: 250)
        }
      }
    }
  }

  func peerSendMutex(_ peer: String) -> AsyncMutex {
    withState { state in
      if let existing = state.peerSendMutexes[peer] { return existing }
      let created = AsyncMutex()
      state.peerSendMutexes[peer] = created
      return created
    }
  }

  // MARK: - Framed sending

  func sendFramed(peer: String, stream: DittoStream, payload: Data) async throws {
    let maxSize = try stream.maxSendSize
    if payload.count <= maxSize {
      try await sendChecked(peer: peer, stream: stream, bytes: payload)
      return
    }
    // Content-addressed transfer id so a re-send of the same payload (after a stream failure)
    // reuses the same id: the receiver re-fills the same assembly instead of leaking an orphaned
    // partial one.
    let transfer = try SnapshotTransfer.create(
      payload,
      maxChunkSize: max(maxSize - 768, 256),
      id: WhiteboardProtocol.sha256(payload).hexString
    )
    for (index, chunk) in transfer.chunks.enumerated() {
      var reliableChunk = Ditto_Whiteboard_V4_ReliableChunk()
      reliableChunk.transferID = transfer.id
      reliableChunk.chunkCount = UInt32(transfer.chunks.count)
      reliableChunk.byteCount = UInt64(payload.count)
      reliableChunk.sha256 = transfer.digest
      reliableChunk.index = UInt32(index)
      reliableChunk.data = chunk
      var envelope = baseEnvelope()
      envelope.reliableChunk = reliableChunk
      let bytes: Data = try envelope.serializedBytes()
      guard bytes.count <= maxSize else {
        throw WhiteboardCoreError.requirementFailed(
          "Ditto maxSendSize is too small for a reliable chunk envelope"
        )
      }
      try await sendChecked(peer: peer, stream: stream, bytes: bytes)
    }
  }

  /// Sends one reliable frame and inspects the terminal `DittoSendStatus`. A `.failed` is thrown
  /// so the outbox loop tears down the stream and re-sends — dropping a "reliable" operation
  /// silently would let boards diverge. A `.cancelled` is only benign when our own task was
  /// cancelled (shutdown): `Task.checkCancellation()` throws in that case and unwinds cleanly. If
  /// the task is still active, a `.cancelled` came from the SDK (e.g. the stream closing
  /// mid-send), so it is treated exactly like `.failed`.
  func sendChecked(peer: String, stream: DittoStream, bytes: Data) async throws {
    switch try await stream.send(bytes) {
    case .sent:
      incrementTx(peer)
    case .failed:
      throw WhiteboardCoreError.requirementFailed("Reliable send to \(peer.suffix(6)) failed")
    case .cancelled:
      try Task.checkCancellation()
      throw WhiteboardCoreError.requirementFailed(
        "Reliable send to \(peer.suffix(6)) was cancelled by the SDK"
      )
    case .unknown:
      throw WhiteboardCoreError.requirementFailed(
        "Reliable send to \(peer.suffix(6)) ended with unknown status"
      )
    case .pending:
      throw WhiteboardCoreError.requirementFailed(
        "Reliable send to \(peer.suffix(6)) returned a non-terminal status"
      )
    @unknown default:
      throw WhiteboardCoreError.requirementFailed(
        "Reliable send to \(peer.suffix(6)) ended with an unrecognized status"
      )
    }
  }

  /// Closes a bad or saturated stream at most once per peer/topic. The active connector's bounded
  /// reconnect loop opens a fresh stream, whose Hello/digest exchange repairs anything not queued.
  func recoverStream(_ peer: String, _ topic: String, _ reason: String) {
    let key = StreamKey(peerKey: peer, topic: topic)
    withState { state in
      guard state.recoveryTasks[key] == nil else { return }
      state.recoveryTasks[key] = spawn { [self] in
        recordError(peer, reason)
        let stream = withState { $0.streams.removeValue(forKey: key) }
        stream?.close()
        withState { $0.recoveryTasks.removeValue(forKey: key) }
      }
    }
  }

  // MARK: - Reliable frame decoding

  /// Decodes one reliable frame from `peer`.
  ///
  /// Deliberately **not** `async`: this runs on the single reliable-ingress worker shared by
  /// every peer, so anything that can block here stalls inbound traffic for the whole mesh and
  /// cascades into stream teardowns. Long-running work is handed to `operationDispatcher` or
  /// `dispatchSnapshotControl`, or spawned, never awaited inline.
  func handleReliable(peer: String, payload: Data, allowChunkEnvelope: Bool = true) {
    switch WhiteboardProtocol.decodeEnvelope(payload, expectedPeerKey: peer) {
    case .incompatible(let version):
      incompatible(peer, version: version)
    case .invalid(let reason):
      recordError(peer, reason)
    case .compatible(let envelope):
      switch envelope.payload {
      case .hello:
        handleHello(peer: peer, envelope: envelope)
      case .operation:
        // Peer-supplied JSON; a malformed operation must be rejected, not thrown.
        do {
          let operation = try WhiteboardProtocol.decodeOperation(envelope, expectedPeerKey: peer)
          if !operationDispatcher.tryDispatch(peer, operation) {
            recoverStream(
              peer,
              WhiteboardProtocol.stateStreamName,
              "Reliable operation application queue saturated"
            )
          }
        } catch {
          recordError(peer, "Malformed reliable operation")
        }
      case .snapshotBegin:
        beginHydration(peer: peer, envelope: envelope)
      case .snapshotChunk(let chunk):
        if chunk.transferID.isBlankTransport || chunk.transferID.utf16.count > maxTransferIdLength {
          recordError(peer, "Invalid snapshot chunk transfer id")
        } else {
          handleSnapshotChunk(peer: peer, chunk: chunk)
        }
      case .snapshotEnd(let end):
        let transferId = end.transferID
        if transferId.isBlankTransport || transferId.utf16.count > maxTransferIdLength {
          recordError(peer, "Invalid snapshot end transfer id")
        } else {
          launchHydrationFinish(peer: peer, transferId: transferId)
        }
      case .snapshotAck(let ack):
        if ack.error.utf16.count > maxDiagnosticLength {
          recordError(peer, "Invalid snapshot acknowledgement error")
        } else {
          handleSnapshotAck(peer: peer, ack: ack)
        }
      case .reliableChunk(let chunk):
        if allowChunkEnvelope {
          handleChunk(peer: peer, chunk: chunk)
        } else {
          recoverStream(peer, WhiteboardProtocol.stateStreamName, "Nested reliable chunks are not allowed")
        }
      case nil:
        break
      }
    }
  }

  // MARK: - Second-tier reliable chunk assembly

  func handleChunk(peer: String, chunk: Ditto_Whiteboard_V4_ReliableChunk) {
    let digest = chunk.sha256
    if let reason = invalidTransferHeader(
      transferId: chunk.transferID,
      chunkCount: Int(chunk.chunkCount),
      byteCount: Int64(chunk.byteCount),
      digest: digest,
      maximumByteCount: Int64(WhiteboardProtocol.maxReliableFrameBytes)
    ) {
      recoverStream(peer, WhiteboardProtocol.stateStreamName, reason)
      return
    }
    let key = ChunkAssemblyKey(peer: peer, transferId: chunk.transferID)
    enum Lookup {
      case existing(ChunkAssembly)
      case created(ChunkAssembly)
      case metadataMismatch
      case overCapacity
    }
    let lookup = withState { state -> Lookup in
      if let existing = state.chunkAssemblies[key] {
        guard existing.expectedCount == Int(chunk.chunkCount),
              existing.expectedByteCount == Int64(chunk.byteCount),
              existing.expectedDigest == digest
        else {
          state.chunkAssemblies.removeValue(forKey: key)
          existing.timeoutTask?.cancel()
          return .metadataMismatch
        }
        return .existing(existing)
      }
      guard state.chunkAssemblies.keys.filter({ $0.peer == peer }).count < maxChunkAssembliesPerPeer
      else {
        return .overCapacity
      }
      // The header was validated above, so assembly construction cannot fail.
      guard let assembler = try? SnapshotAssembler(
        transferId: chunk.transferID,
        chunkCount: Int(chunk.chunkCount),
        byteCount: Int64(chunk.byteCount),
        expectedDigest: chunk.sha256
      ) else {
        return .metadataMismatch
      }
      let created = ChunkAssembly(
        assembler: assembler,
        expectedCount: Int(chunk.chunkCount),
        expectedByteCount: Int64(chunk.byteCount),
        expectedDigest: digest
      )
      state.chunkAssemblies[key] = created
      return .created(created)
    }
    let assembly: ChunkAssembly
    switch lookup {
    case .existing(let existing):
      assembly = existing
    case .created(let created):
      assembly = created
      resetReliableTransferTimeout(peer: peer, key: key, assembly: created)
    case .metadataMismatch:
      recoverStream(peer, WhiteboardProtocol.stateStreamName, "Reliable transfer metadata changed mid-stream")
      return
    case .overCapacity:
      recoverStream(peer, WhiteboardProtocol.stateStreamName, "Too many incomplete reliable transfers")
      return
    }
    switch assembly.assembler.add(index: Int(chunk.index), bytes: chunk.data) {
    case .rejected(let reason):
      withState { state in
        if state.chunkAssemblies[key] === assembly {
          state.chunkAssemblies.removeValue(forKey: key)
        }
      }
      assembly.timeoutTask?.cancel()
      recoverStream(peer, WhiteboardProtocol.stateStreamName, reason)
      return
    default:
      break
    }
    assembly.received.insert(Int(chunk.index))
    resetReliableTransferTimeout(peer: peer, key: key, assembly: assembly)
    if assembly.received.count == assembly.expectedCount {
      withState { state in
        if state.chunkAssemblies[key] === assembly {
          state.chunkAssemblies.removeValue(forKey: key)
        }
      }
      assembly.timeoutTask?.cancel()
      switch assembly.assembler.finish(chunk.transferID) {
      case .complete(let bytes):
        handleReliable(peer: peer, payload: bytes, allowChunkEnvelope: false)
      case .rejected(let reason):
        recoverStream(peer, WhiteboardProtocol.stateStreamName, reason)
      case .pending:
        break
      }
    }
  }

  func resetReliableTransferTimeout(peer: String, key: ChunkAssemblyKey, assembly: ChunkAssembly) {
    withState { state in
      guard state.chunkAssemblies[key] === assembly else { return }
      assembly.timeoutTask?.cancel()
      let ageMillis = Int64((uptimeNanos() - assembly.startedAtNanos) / 1_000_000)
      let remainingLifetime = maxReliableTransferLifetimeMillis - ageMillis
      assembly.timeoutTask = spawn { [self] in
        await sleep(milliseconds: min(reliableTransferTimeoutMillis, max(remainingLifetime, 0)))
        guard !Task.isCancelled else { return }
        let removed = withState { state -> Bool in
          guard state.chunkAssemblies[key] === assembly else { return false }
          state.chunkAssemblies.removeValue(forKey: key)
          return true
        }
        if removed {
          recoverStream(peer, WhiteboardProtocol.stateStreamName, "Reliable transfer timed out; reconciling")
        }
      }
    }
  }

  // MARK: - Operation application

  func handleOperation(peer: String, operation: BoardOperation) async throws {
    if operation.stamp.lamport > WhiteboardProtocol.maxSessionLamport {
      recoverStream(peer, WhiteboardProtocol.stateStreamName, "Operation exceeds the board logical-time limit")
      return
    }
    var rejectedHydration: SnapshotHydration?
    let buffered = withState { state -> Bool in
      guard let hydration = state.hydrations[peer] else { return false }
      let existing = hydration.bufferedOperations[operation.id]
      let winner = existing.map { canonicalOperation($0, operation) } ?? operation
      let byteDelta = WhiteboardProtocol.operationJsonByteCount(winner)
        - (existing.map { WhiteboardProtocol.operationJsonByteCount($0) } ?? 0)
      if (existing == nil && hydration.bufferedOperations.count >= maxBufferedHydrationOperations)
        || hydration.bufferedOperationBytes + byteDelta > maxBufferedHydrationBytes
      {
        if state.hydrations[peer] === hydration {
          state.hydrations.removeValue(forKey: peer)
          hydration.timeoutTask?.cancel()
          hydration.finishTask?.cancel()
          rejectedHydration = hydration
        }
        return false
      }
      hydration.bufferedOperations[operation.id] = winner
      hydration.bufferedOperationBytes += byteDelta
      return true
    }
    if buffered { return }
    if let rejectedHydration {
      try await rejectRemovedHydration(
        peer: peer,
        hydration: rejectedHydration,
        reason: "Too many operations arrived during snapshot hydration"
      )
    }
    try await deliverOperation(peer: peer, operation: operation)
  }

  func deliverOperation(peer: String, operation: BoardOperation) async throws {
    let prepared = HandshakeSignal()
    let committed = HandshakeSignal()
    let applied = HandshakeSignal()
    defer {
      // Presence/background cancellation must never strand BoardSession awaiting one phase.
      prepared.complete(false)
      committed.complete(false)
      applied.complete(false)
    }
    eventsBroadcast.yield(
      .reliableOperationReceived(
        operation: operation,
        prepared: prepared,
        committed: committed,
        applied: applied
      )
    )
    guard try await awaitHandshake(prepared) else {
      committed.complete(false)
      recoverStream(peer, WhiteboardProtocol.stateStreamName, "Reliable operation was not applied by the board session")
      return
    }
    let result = knownLog.accept(operation)
    if case .rejected(let reason) = result {
      committed.complete(false)
      recoverStream(peer, WhiteboardProtocol.stateStreamName, reason)
      return
    }
    committed.complete(true)
    guard try await awaitHandshake(applied) else {
      recoverStream(peer, WhiteboardProtocol.stateStreamName, "Reliable operation apply acknowledgement timed out")
      return
    }
    if case .profileUpdate(let update) = operation {
      updatePeer(peer) { entry in
        entry.displayName = update.profile.displayName
        entry.colorArgb = update.profile.colorArgb
      }
    }
  }

  func awaitHandshake(_ signal: HandshakeSignal) async throws -> Bool {
    try await withTimeout(milliseconds: transferInactivityTimeoutMillis) {
      try await signal.wait()
    } ?? false
  }

  func baseEnvelope() -> Ditto_Whiteboard_V4_Envelope {
    var envelope = Ditto_Whiteboard_V4_Envelope()
    envelope.protocolVersion = UInt32(WhiteboardProtocol.protocolVersion)
    envelope.boardID = boardID
    envelope.senderPeerKey = localPeerKey
    envelope.senderSequence = UInt64(bitPattern: nextControlSequence())
    return envelope
  }

  func invalidTransferHeader(
    transferId: String,
    chunkCount: Int,
    byteCount: Int64,
    digest: Data,
    maximumByteCount: Int64 = maxTransferBytes
  ) -> String? {
    if transferId.isBlankTransport || transferId.utf16.count > maxTransferIdLength {
      return "Invalid transfer id"
    }
    if !(1...maxTransferChunks).contains(chunkCount) {
      return "Invalid transfer chunk count"
    }
    if !(1...maximumByteCount).contains(byteCount) {
      return "Invalid transfer byte count"
    }
    if digest.count != sha256ByteCount {
      return "Invalid transfer digest"
    }
    return nil
  }
}
