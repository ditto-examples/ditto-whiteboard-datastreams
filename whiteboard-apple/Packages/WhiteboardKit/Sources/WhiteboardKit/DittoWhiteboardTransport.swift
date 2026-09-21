import Foundation
import WhiteboardCore
@_spi(PreviewDataStreams) import DittoSwift

let reliableOutboxCapacity = 64
let reliableInboundCapacity = 64
let liveInboundCapacity = 128
let operationApplicationCapacityPerPeer = 32
let snapshotControlCapacityPerPeer = 8
let maxBufferedHydrationOperations = 64
let maxBufferedHydrationBytes = 2 * 1024 * 1024
let maxChunkAssembliesPerPeer = 4
let reliableTransferTimeoutMillis: Int64 = 10_000
let maxReliableTransferLifetimeMillis: Int64 = 60_000
let helloMinIntervalNanos: UInt64 = 250_000_000
let initialSyncTimeoutMillis: Int64 = 30_000
let maxDiagnosticLength = 256
/// Give up re-sending a single reliable frame after this many stream failures and drop it.
let maxSendAttempts = 5
/// Upper bound on 100ms polls for a visible peer with no wb_state stream before dropping a frame.
let maxStreamlessWaits = 50

struct StreamKey: Hashable, Sendable {
  let peerKey: String
  let topic: String
}

struct ChunkAssemblyKey: Hashable, Sendable {
  let peer: String
  let transferId: String
}

struct InboundFrame: Sendable {
  let peer: String
  let payload: Data
}

final class SnapshotHydration: @unchecked Sendable {
  let transferId: String
  var assembler: SnapshotAssembler
  let chunkCount: Int
  var receivedChunks: Set<Int> = []
  var bufferedOperations: [OperationId: BoardOperation] = [:]
  var bufferedOperationBytes: Int = 0
  let startedAtNanos: UInt64 = uptimeNanos()
  var timeoutTask: Task<Void, Never>?
  var finishTask: Task<Void, Never>?
  var finishing = false

  init(transferId: String, assembler: SnapshotAssembler, chunkCount: Int) {
    self.transferId = transferId
    self.assembler = assembler
    self.chunkCount = chunkCount
  }
}

final class ChunkAssembly: @unchecked Sendable {
  var assembler: SnapshotAssembler
  var received: Set<Int> = []
  let expectedCount: Int
  let expectedByteCount: Int64
  let expectedDigest: Data
  let startedAtNanos: UInt64 = uptimeNanos()
  var timeoutTask: Task<Void, Never>?

  init(
    assembler: SnapshotAssembler,
    expectedCount: Int,
    expectedByteCount: Int64,
    expectedDigest: Data
  ) {
    self.assembler = assembler
    self.expectedCount = expectedCount
    self.expectedByteCount = expectedByteCount
    self.expectedDigest = expectedDigest
  }
}

final class OutboundSnapshot: @unchecked Sendable {
  let transferId: String
  var timeoutTask: Task<Void, Never>?

  init(transferId: String) {
    self.transferId = transferId
  }
}

struct SnapshotMaterial: Sendable {
  let version: Int64
  let state: BoardState
  let bytes: Data
  let byteDigest: Data
}

func cancelTasks<Key: Hashable>(
  _ tasks: inout [Key: Task<Void, Never>],
  where predicate: (Key) -> Bool
) {
  for key in tasks.keys.filter(predicate) {
    tasks.removeValue(forKey: key)?.cancel()
  }
}

extension String {
  var isBlankTransport: Bool { allSatisfy(\.isWhitespace) }
}

extension Data {
  var hexString: String { map { String(format: "%02x", $0) }.joined() }
  var tracePrefix: String { prefix(4).map { String(format: "%02x", $0) }.joined() }
}

func metadataString(_ value: Any??) -> String? {
  guard case .some(.some(let wrapped)) = value else { return nil }
  return wrapped as? String
}

func metadataInt(_ value: Any??) -> Int? {
  guard case .some(.some(let wrapped)) = value, !(wrapped is Bool) else { return nil }
  return (wrapped as? NSNumber)?.intValue
}

/// The real `WhiteboardTransport`, and the heart of this Data Streams demo: a faithful port of
/// `DittoWhiteboardTransport.kt`. It binds two Data Streams topics on every peer and mirrors
/// board traffic across the nearby mesh: `wb_live` (unreliable, fire-and-forget previews) and
/// `wb_state` (reliable, operations plus late-join snapshots with digest reconciliation).
///
/// Connection ownership is driven by presence: the peer with the lower public key connects, the
/// higher key accepts via `bindTopic`. Nothing is written to the Ditto store — the board is
/// entirely ephemeral.
public final class DittoWhiteboardTransport: WhiteboardTransport, @unchecked Sendable {
  let ditto: Ditto
  public let localPeerKey: String
  public let requiredPermissions: [String] = []

  private let transportConfigCustomizer: (@Sendable (inout DittoTransportConfig) -> Void)?

  let taskRegistry = TaskRegistry()
  let lifecycleMutex = AsyncMutex()
  let lifecycleCoordinator: TransportLifecycleCoordinator
  let outboxVisibility = PeerVisibilityGate()

  let eventsBroadcast = AsyncBroadcast<TransportEvent>()
  public var events: AsyncStream<TransportEvent> { eventsBroadcast.stream }

  private let diagnosticsLock = NSLock()
  private var diagnosticsValue: TransportDiagnostics
  private let diagnosticsBroadcast: AsyncBroadcast<TransportDiagnostics>
  public var diagnostics: AsyncStream<TransportDiagnostics> { diagnosticsBroadcast.stream }
  public var currentDiagnostics: TransportDiagnostics {
    diagnosticsLock.withLock { diagnosticsValue }
  }

  struct State {
    var acceptors: [DittoAcceptor] = []
    var streams: [StreamKey: DittoStream] = [:]
    var connectTasks: [StreamKey: Task<Void, Never>] = [:]
    var acceptOpenTasks: [UUID: Task<Void, Never>] = [:]
    var outboxes: [String: BoundedChannel<Data>] = [:]
    var outboxTasks: [String: Task<Void, Never>] = [:]
    var peerSendMutexes: [String: AsyncMutex] = [:]
    var presenceObserver: DittoObserver?
    var hydrations: [String: SnapshotHydration] = [:]
    var snapshotTasks: [String: Task<Void, Never>] = [:]
    var reciprocalSnapshotTasks: [String: Task<Void, Never>] = [:]
    var chunkAssemblies: [ChunkAssemblyKey: ChunkAssembly] = [:]
    var recoveryTasks: [StreamKey: Task<Void, Never>] = [:]
    var snapshotRetryTasks: [String: Task<Void, Never>] = [:]
    var hydrationSlotWaitTasks: [String: Task<Void, Never>] = [:]
    var queuedSnapshotTasks: [String: Task<Void, Never>] = [:]
    var outboundSnapshotRetryTasks: [String: Task<Void, Never>] = [:]
    var snapshotRetryAttempts = SnapshotRetryBudget()
    var outboundSnapshotRetryAttempts = SnapshotRetryBudget()
    var lastHelloNanos: [String: UInt64] = [:]
    var pendingHellos: [String: Ditto_Whiteboard_V4_Envelope] = [:]
    var lastRemoteDigests: [String: Data] = [:]
    var helloCoalesceTasks: [String: Task<Void, Never>] = [:]
    var outboundSnapshots: [String: OutboundSnapshot] = [:]
    var snapshotRequested: Set<String> = []
    var incompatiblePeerKeys: Set<String> = []
    var synchronizedPeerKeys: Set<String> = []
    var awaitingInitialSyncPeerKeys: Set<String> = []
    var readinessWaivedPeerKeys: Set<String> = []
    var initialReadinessResolved = false
    var initialSyncTimeoutTask: Task<Void, Never>?
    var readinessDelayTask: Task<Void, Never>?
    var readinessGeneration: Int64 = 0
    var txCounters: [String: Int64] = [:]
    var rxCounters: [String: Int64] = [:]
    var profile: UserProfile?
    var visiblePeers: Set<String> = []
    /// Last successful `system:data_sync_info` read, returned when a later read fails or
    /// times out (the presence viewer's sync rows should degrade to last-known, not blank).
    var lastSyncStatusByPeerKey: [String: PeerSyncStatus] = [:]
    var started = false
    var ready = false
    var localOnly = false
    var foreground = true
    var foregroundGeneration: Int64 = 0
    var nearbyNetworkingRunning = false
    var closed = false
  }

  private let stateLock = NSLock()
  var state = State()

  @discardableResult
  func withState<T>(_ body: (inout State) -> T) -> T {
    stateLock.lock()
    defer { stateLock.unlock() }
    return body(&state)
  }

  let knownLog = KnownLogBox()
  let snapshotMaterialMutex = AsyncMutex()
  var cachedSnapshotMaterial: SnapshotMaterial?

  private let sequenceLock = NSLock()
  private var controlSequence: Int64 = 0
  private var liveSequence: Int64 = 0
  let liveSessionId = UUID().uuidString

  let reliableInbound = BoundedChannel<InboundFrame>(capacity: reliableInboundCapacity)
  let liveInbound = BoundedChannel<InboundFrame>(capacity: liveInboundCapacity, policy: .dropOldest)
  let pendingLive = BoundedChannel<LivePreview>(capacity: 1, policy: .dropOldest)

  var operationDispatcher: PerPeerOperationDispatcher<BoardOperation>!
  var snapshotControlDispatcher: PerPeerOperationDispatcher<@Sendable () async throws -> Void>!

  public init(
    credentials: DittoCredentials,
    transportConfigCustomizer: (@Sendable (inout DittoTransportConfig) -> Void)? = nil
  ) throws {
    let config = DittoConfig(
      databaseID: credentials.databaseID,
      connect: .smallPeersOnly(privateKey: nil),
      persistenceDirectory: credentials.persistenceDirectory,
      systemParameters: [
        "network_enable_ngn": .bool(true),
        "replication_over_ngn": .bool(true),
      ]
    )
    let ditto = try Ditto.openSync(config: config)
    try ditto.setOfflineOnlyLicenseToken(credentials.offlineLicenseToken)
    self.ditto = ditto
    self.transportConfigCustomizer = transportConfigCustomizer
    self.localPeerKey = ditto.presence.graph.localPeer.peerKey
    // Apple needs no runtime permissions for nearby transports; the coordinator starts granted.
    self.lifecycleCoordinator = TransportLifecycleCoordinator(
      permissionsGranted: true,
      requestedForeground: true
    )
    let initialDiagnostics = TransportDiagnostics(
      localPeerKey: self.localPeerKey,
      editingReady: false,
      mode: .nearbyMesh
    )
    self.diagnosticsValue = initialDiagnostics
    self.diagnosticsBroadcast = AsyncBroadcast(replayLatest: true, latest: initialDiagnostics)

    operationDispatcher = PerPeerOperationDispatcher<BoardOperation>(
      capacityPerPeer: operationApplicationCapacityPerPeer
    ) { [self] peer, operation in
      do {
        try await handleOperation(peer: peer, operation: operation)
      } catch is CancellationError {
      } catch {
        recordError(peer, error)
        recoverStream(peer, WhiteboardProtocol.stateStreamName, "Reliable operation application failed")
      }
    }
    // Snapshot rejections replay buffered operations through the session's prepare/commit/apply
    // handshake and enqueue an acknowledgement onto a bounded outbox. Both can block for tens of
    // seconds, so they must never run on the single reliable-ingress worker. Ordered per peer and
    // bounded, exactly like the operation lanes.
    snapshotControlDispatcher = PerPeerOperationDispatcher<@Sendable () async throws -> Void>(
      capacityPerPeer: snapshotControlCapacityPerPeer
    ) { [self] peer, work in
      do {
        try await work()
      } catch is CancellationError {
      } catch {
        recordError(peer, error)
      }
    }

    configureTransports()
    startWorkers()
  }

  // MARK: - Public API

  public func start(profile: UserProfile) async {
    do {
      try await lifecycleMutex.withLock {
        withState { $0.profile = profile }
        let target = lifecycleCoordinator.snapshot()
        if withState({ $0.started }) {
          try await reconcileLifecycleTarget(target)
          return
        }
        withState { state in
          state.foreground = target.requestedForeground
          state.localOnly = !target.permissionsGranted || !target.permissionsReadyForSync
        }
        if withState({ $0.localOnly }) {
          withState { $0.started = true }
          let running = withState { $0.foreground }
          updateDiagnostics { current in
            var next = current
            next.running = running
            next.editingReady = true
            next.mode = .localPreview
            next.connectivityMessage = "Nearby collaboration is off for this session."
            return next
          }
          return
        }
        withState { $0.started = true }
        try await reconcileLifecycleTarget(target)
      }
    } catch is CancellationError {
    } catch {
      updateDiagnostics { current in
        var next = current
        next.running = false
        next.connectivityMessage = safeDiagnostic("Nearby lifecycle update failed: \(error.localizedDescription)")
        return next
      }
    }
  }

  public func sendReliable(_ operation: BoardOperation) async -> Bool {
    if case .rejected = knownLog.accept(operation) { return false }
    // Local fallback edits remain in the authoritative digest/log; only radio fan-out is skipped.
    if withState({ $0.localOnly }) { return true }
    guard let bytes = try? WhiteboardProtocol.operationEnvelope(operation) else { return true }
    // Never create one potentially-suspended task per operation and peer. A saturated peer drops
    // this enqueue, closes its state stream, and reconciles from the next Hello/snapshot.
    let peers = withState { state in
      Set(state.streams.keys.filter { $0.topic == WhiteboardProtocol.stateStreamName }.map(\.peerKey))
    }
    for peer in peers {
      if !tryEnqueueOutbox(peer, bytes) {
        recoverStream(peer, WhiteboardProtocol.stateStreamName, "Reliable outbox saturated; reconciling from snapshot")
      }
    }
    return true
  }

  public func sendLive(_ preview: LivePreview) {
    if withState({ $0.localOnly }) { return }
    var trimmed = preview
    trimmed.points = Array(preview.points.suffix(32))
    _ = pendingLive.trySend(trimmed)
  }

  public func setForeground(_ isForeground: Bool) {
    guard lifecycleCoordinator.requestForeground(isForeground) else { return }
    let generation = withState { state -> Int64 in
      state.foregroundGeneration += 1
      return state.foregroundGeneration
    }
    _ = spawn { [self] in
      do {
        try await lifecycleMutex.withLock {
          guard withState({ $0.foregroundGeneration }) == generation else { return }
          try await reconcileLifecycleTarget(lifecycleCoordinator.snapshot())
        }
      } catch is CancellationError {
      } catch {
        updateDiagnostics { current in
          var next = current
          next.running = false
          next.connectivityMessage = safeDiagnostic("Nearby lifecycle update failed: \(error.localizedDescription)")
          return next
        }
      }
    }
  }

  /// Ordered teardown per SKILL.md: cancel connection/retry work, close streams, close acceptors,
  /// cancel the remaining workers, then close Ditto last so no task touches a closed endpoint.
  public func close() {
    let alreadyClosed = withState { state -> Bool in
      if state.closed { return true }
      state.closed = true
      state.started = false
      return false
    }
    guard !alreadyClosed else { return }
    _ = lifecycleCoordinator.requestForeground(false)
    withState { state in
      state.foregroundGeneration += 1
      cancelTasks(&state.connectTasks) { _ in true }
    }
    outboxVisibility.disable { [self] in
      withState { state in
        cancelTasks(&state.outboxTasks) { _ in true }
        for channel in state.outboxes.values { channel.close() }
        state.outboxes.removeAll()
        state.peerSendMutexes.removeAll()
      }
    }
    withState { state in
      state.presenceObserver?.stop()
      state.presenceObserver = nil
      cancelTasks(&state.snapshotTasks) { _ in true }
      cancelTasks(&state.reciprocalSnapshotTasks) { _ in true }
      cancelTasks(&state.snapshotRetryTasks) { _ in true }
      cancelTasks(&state.hydrationSlotWaitTasks) { _ in true }
      cancelTasks(&state.queuedSnapshotTasks) { _ in true }
      cancelTasks(&state.outboundSnapshotRetryTasks) { _ in true }
      cancelTasks(&state.helloCoalesceTasks) { _ in true }
      state.pendingHellos.removeAll()
      state.lastRemoteDigests.removeAll()
      state.snapshotRetryAttempts.clear()
      state.outboundSnapshotRetryAttempts.clear()
      state.snapshotRequested.removeAll()
      state.lastHelloNanos.removeAll()
      cancelTasks(&state.acceptOpenTasks) { _ in true }
      state.synchronizedPeerKeys.removeAll()
      state.awaitingInitialSyncPeerKeys.removeAll()
      state.readinessWaivedPeerKeys.removeAll()
      state.readinessDelayTask?.cancel()
      state.readinessDelayTask = nil
      state.initialSyncTimeoutTask?.cancel()
      state.initialSyncTimeoutTask = nil
      state.readinessGeneration += 1
      for outbound in state.outboundSnapshots.values { outbound.timeoutTask?.cancel() }
      state.outboundSnapshots.removeAll()
      cancelTasks(&state.recoveryTasks) { _ in true }
      for hydration in state.hydrations.values {
        hydration.timeoutTask?.cancel()
        hydration.finishTask?.cancel()
      }
      state.hydrations.removeAll()
      for assembly in state.chunkAssemblies.values { assembly.timeoutTask?.cancel() }
      state.chunkAssemblies.removeAll()
      for stream in state.streams.values { stream.close() }
      state.streams.removeAll()
      for acceptor in state.acceptors { acceptor.close() }
      state.acceptors.removeAll()
    }
    operationDispatcher.close()
    snapshotControlDispatcher.close()
    taskRegistry.cancelAll()
    ditto.sync.stop()
    ditto.close()
    updateDiagnostics { current in
      var next = current
      next.running = false
      return next
    }
    eventsBroadcast.finish()
    diagnosticsBroadcast.finish()
  }

  // MARK: - Lifecycle reconciliation

  func reconcileLifecycleTarget(_ target: TransportLifecycleCoordinator.Snapshot) async throws {
    withState { $0.foreground = target.requestedForeground }
    guard withState({ $0.started }) else { return }
    if withState({ $0.localOnly }) {
      if withState({ $0.nearbyNetworkingRunning }) { await pauseStartedNetworking() }
      let running = withState { $0.foreground }
      updateDiagnostics { current in
        var next = current
        next.running = running
        next.editingReady = true
        return next
      }
      return
    }
    guard target.permissionsReadyForSync else {
      if withState({ $0.nearbyNetworkingRunning }) { await pauseStartedNetworking() }
      updateDiagnostics { current in
        var next = current
        next.running = false
        return next
      }
      return
    }
    if let profile = withState({ $0.profile }) {
      try prepareNearbyNetworking(profile: profile)
    }
    let snapshot = withState { ($0.started, $0.localOnly, $0.nearbyNetworkingRunning) }
    if target.shouldRunNearby(started: snapshot.0, localOnly: snapshot.1) {
      if snapshot.2 {
        updateDiagnostics { current in
          var next = current
          next.running = true
          return next
        }
        return
      }
      outboxVisibility.enable()
      // NGN sync.start() synchronously spawns the UDP transport; see runOnLargeStack.
      try await runOnLargeStack { [ditto] in try ditto.sync.start() }
      withState { $0.nearbyNetworkingRunning = true }
      updateDiagnostics { current in
        var next = current
        next.running = true
        return next
      }
      beginReadinessWindow()
      let peers = withState { state in
        state.visiblePeers
          .filter { !state.incompatiblePeerKeys.contains($0) && localPeerKey < $0 }
          .sorted()
      }
      for peer in peers {
        ensureConnectJob(StreamKey(peerKey: peer, topic: WhiteboardProtocol.liveStreamName), reliability: .unreliable)
        ensureConnectJob(StreamKey(peerKey: peer, topic: WhiteboardProtocol.stateStreamName), reliability: .reliable)
      }
    } else if snapshot.2 {
      await pauseStartedNetworking()
    } else {
      updateDiagnostics { current in
        var next = current
        next.running = false
        return next
      }
    }
  }

  func pauseStartedNetworking() async {
    // Close the admission gate before tearing resources down so presence/candidate callbacks
    // racing this method cannot recreate streams behind cleanup.
    withState { state in
      state.nearbyNetworkingRunning = false
      state.ready = false
      state.initialReadinessResolved = false
      state.readinessDelayTask?.cancel()
      state.readinessDelayTask = nil
      state.initialSyncTimeoutTask?.cancel()
      state.initialSyncTimeoutTask = nil
      state.readinessGeneration += 1
      state.awaitingInitialSyncPeerKeys.removeAll()
      state.readinessWaivedPeerKeys.removeAll()
      state.synchronizedPeerKeys.removeAll()
    }
    updateDiagnostics { current in
      var next = current
      next.editingReady = false
      next.connectivityMessage = connectivityMessageAfterNewInitialSyncGate(
        currentMessage: current.connectivityMessage,
        newGateStarted: true
      )
      return next
    }
    pauseNearbyNetworking()
    ditto.sync.stop()
    updateDiagnostics { current in
      var next = current
      next.running = false
      for (key, peer) in next.peers {
        var updated = peer
        updated.liveConnected = false
        updated.stateConnected = false
        next.peers[key] = updated
      }
      return next
    }
  }

  func pauseNearbyNetworking() {
    withState { state in
      cancelTasks(&state.connectTasks) { _ in true }
      cancelTasks(&state.recoveryTasks) { _ in true }
      cancelTasks(&state.snapshotTasks) { _ in true }
      cancelTasks(&state.reciprocalSnapshotTasks) { _ in true }
      cancelTasks(&state.snapshotRetryTasks) { _ in true }
      cancelTasks(&state.hydrationSlotWaitTasks) { _ in true }
      cancelTasks(&state.queuedSnapshotTasks) { _ in true }
      state.snapshotRetryAttempts.clear()
      cancelTasks(&state.outboundSnapshotRetryTasks) { _ in true }
      state.outboundSnapshotRetryAttempts.clear()
      state.snapshotRequested.removeAll()
      cancelTasks(&state.helloCoalesceTasks) { _ in true }
      state.pendingHellos.removeAll()
      state.lastRemoteDigests.removeAll()
      cancelTasks(&state.acceptOpenTasks) { _ in true }
      for outbound in state.outboundSnapshots.values { outbound.timeoutTask?.cancel() }
      state.outboundSnapshots.removeAll()
      for stream in state.streams.values { stream.close() }
      state.streams.removeAll()
    }
    outboxVisibility.disable { [self] in
      withState { state in
        cancelTasks(&state.outboxTasks) { _ in true }
        for channel in state.outboxes.values { channel.close() }
        state.outboxes.removeAll()
        state.peerSendMutexes.removeAll()
      }
    }
    operationDispatcher.close()
    snapshotControlDispatcher.close()
    withState { state in
      for hydration in state.hydrations.values {
        hydration.timeoutTask?.cancel()
        hydration.finishTask?.cancel()
      }
      state.hydrations.removeAll()
      for assembly in state.chunkAssemblies.values { assembly.timeoutTask?.cancel() }
      state.chunkAssemblies.removeAll()
    }
  }

  func beginReadinessWindow() {
    withState { state in
      state.ready = false
      state.initialReadinessResolved = false
      state.readinessDelayTask?.cancel()
      state.initialSyncTimeoutTask?.cancel()
      state.initialSyncTimeoutTask = nil
      state.readinessGeneration += 1
      state.awaitingInitialSyncPeerKeys.removeAll()
      state.readinessWaivedPeerKeys.removeAll()
      state.synchronizedPeerKeys.removeAll()
    }
    updateDiagnostics { current in
      var next = current
      next.editingReady = false
      if next.connectivityMessage?.hasPrefix("Initial nearby sync timed out") == true {
        next.connectivityMessage = nil
      }
      return next
    }
    let delayTask = spawn { [self] in
      await sleep(milliseconds: 1_000)
      guard !Task.isCancelled, withState({ $0.foreground && $0.started }) else { return }
      let gatedPeers = withState { state in
        state.visiblePeers.filter { !state.incompatiblePeerKeys.contains($0) }.sorted()
      }
      withState { state in
        for peer in gatedPeers {
          let synchronized = state.synchronizedPeerKeys
          let waived = state.readinessWaivedPeerKeys
          _ = tryAwaitInitialSync(
            peer: peer,
            synchronizedPeers: { synchronized },
            waivedPeers: { waived },
            awaitingPeers: &state.awaitingInitialSyncPeerKeys
          )
        }
        state.ready = true
      }
      refreshEditingReadiness()
      let statePeers = withState { state in
        state.streams.keys.filter { $0.topic == WhiteboardProtocol.stateStreamName }.map(\.peerKey)
      }
      for peer in statePeers { await sendHello(peer) }
    }
    withState { $0.readinessDelayTask = delayTask }
  }

  // MARK: - Ditto setup

  private func configureTransports() {
    ditto.updateTransportConfig { config in
      config.peerToPeer.bluetoothLE.isEnabled = true
      config.peerToPeer.lan.isEnabled = true
      // Keep Apple in parity with Android and Edge Studio: the LAN transport uses
      // both mDNS and ordinary IP-multicast discovery.  This is distinct from
      // Ditto's opt-in `multicastBeta` data transport and needs no entitlement.
      config.peerToPeer.lan.isMDNSEnabled = true
      config.peerToPeer.lan.isMulticastEnabled = true
      // Apple has no Wi-Fi Aware; AWDL is the nearest equivalent.
      config.peerToPeer.awdl.isEnabled = true
      config.listen.tcp.isEnabled = false
      transportConfigCustomizer?(&config)
    }
  }

  private func publishMetadata(profile: UserProfile) throws {
    try ditto.presence.setPeerMetadata([
      "application": "ditto-whiteboard",
      "protocolVersion": WhiteboardProtocol.protocolVersion,
      "boardId": boardID,
      "displayName": profile.displayName,
      "colorArgb": Int(profile.colorArgb),
    ])
  }

  private func prepareNearbyNetworking(profile: UserProfile) throws {
    ditto.deviceName = profile.displayName
    try publishMetadata(profile: profile)
    try installAcceptors()
    observePresence()
  }

  private func installAcceptors() throws {
    guard withState({ $0.acceptors.isEmpty }) else { return }
    let liveAcceptor = try bind(WhiteboardProtocol.liveStreamName, reliability: .unreliable)
    let stateAcceptor = try bind(WhiteboardProtocol.stateStreamName, reliability: .reliable)
    withState { $0.acceptors.append(contentsOf: [liveAcceptor, stateAcceptor]) }
  }

  private func bind(_ topic: String, reliability: DittoReliability) throws -> DittoAcceptor {
    try ditto.dataStreams.bindTopic(topic, reliability: reliability) { [self] borrowedCandidate in
      guard let peer = try? borrowedCandidate.peerKey else { return }
      let admitted = withState { state in
        isNearbyLifecycleActive(
          started: state.started,
          foreground: state.foreground,
          nearbyNetworkingRunning: state.nearbyNetworkingRunning,
          localOnly: state.localOnly
        )
          && state.visiblePeers.contains(peer)
          && peer < localPeerKey
          && !state.incompatiblePeerKeys.contains(peer)
      }
      guard admitted else { return }
      let candidate = borrowedCandidate.take()
      let id = UUID()
      let task = spawn { [self] in
        defer { withState { $0.acceptOpenTasks.removeValue(forKey: id) } }
        do {
          guard withState({ state in
            isNearbyLifecycleActive(
              started: state.started,
              foreground: state.foreground,
              nearbyNetworkingRunning: state.nearbyNetworkingRunning,
              localOnly: state.localOnly
            ) && state.visiblePeers.contains(peer)
          }) else {
            candidate.close()
            return
          }
          let stream = try candidate.open { [self] inbound in
            if let payload = try? inbound.payload {
              enqueueInbound(peer: peer, topic: topic, payload: payload)
            }
          }
          candidate.close()
          guard withState({ state in
            isNearbyLifecycleActive(
              started: state.started,
              foreground: state.foreground,
              nearbyNetworkingRunning: state.nearbyNetworkingRunning,
              localOnly: state.localOnly
            ) && state.visiblePeers.contains(peer)
          }) else {
            stream.close()
            return
          }
          await registerAndWait(key: StreamKey(peerKey: peer, topic: topic), stream: stream)
        } catch is CancellationError {
        } catch {
          recordError(peer, error)
        }
      }
      withState { $0.acceptOpenTasks[id] = task }
    }
  }

  // MARK: - Presence

  private func observePresence() {
    guard withState({ $0.presenceObserver == nil }) else { return }
    traceReadiness("observePresence installing")
    // Unlike Kotlin's flow, Swift presence observation exposes no failure signal to retry on.
    let observer = ditto.presence.observe { [self] graph in
      traceReadiness("presence graph fired: \(graph.remotePeers.count) remote peer(s)")
      handlePresenceGraph(graph)
    }
    withState { $0.presenceObserver = observer }
  }

  private func handlePresenceGraph(_ graph: DittoPresenceGraph) {
    #if DEBUG
    for peer in graph.remotePeers {
      let meta = peer.peerMetadata
      let app = metadataString(meta["application"]) ?? "nil"
      let board = metadataString(meta["boardId"]) ?? "nil"
      let ver = metadataInt(meta["protocolVersion"]).map(String.init) ?? "nil"
      traceReadiness(
        "presence peer=\(peer.peerKey.prefix(12))… app=\(app) board=\(board) version=\(ver) "
          + "connected=\(peer.isConnectedToDittoServer) connections=\(peer.connections.count) "
          + "rawMetadataKeys=\(meta.keys.sorted())")
    }
    #endif
    let discoveredWhiteboardPeers = graph.remotePeers.filter { peer in
      metadataString(peer.peerMetadata["application"]) == "ditto-whiteboard"
        && metadataString(peer.peerMetadata["boardId"]) == boardID
    }
    // Every process selects the same lowest ten keys (including itself), avoiding asymmetric
    // connect attempts and bounding all per-peer queues/jobs even in a crowded radio space.
    let admittedKeys = admittedPeerKeys(
      localPeerKey: localPeerKey,
      discoveredPeerKeys: discoveredWhiteboardPeers.map(\.peerKey)
    )
    let whiteboardPeers = admittedKeys.isEmpty
      ? [DittoPeer]()
      : discoveredWhiteboardPeers.filter { admittedKeys.contains($0.peerKey) }
    let capacityLimited = discoveredWhiteboardPeers.count > whiteboardPeers.count
    var metadataIncompatible: [String: Int] = [:]
    for peer in whiteboardPeers {
      if let version = metadataInt(peer.peerMetadata["protocolVersion"]),
         version != WhiteboardProtocol.protocolVersion
      {
        metadataIncompatible[peer.peerKey] = version
      }
    }
    withState { $0.incompatiblePeerKeys.formUnion(metadataIncompatible.keys) }
    let incompatibleNow = withState { $0.incompatiblePeerKeys }
    let remote = whiteboardPeers.filter {
      metadataIncompatible[$0.peerKey] == nil && !incompatibleNow.contains($0.peerKey)
    }
    let presentPeerKeys = Set(whiteboardPeers.map(\.peerKey))
    let connections = admittedPresenceConnections(
      localPeerKey: localPeerKey,
      presentPeerKeys: presentPeerKeys,
      connections: graph.remotePeers.flatMap(\.connections).map {
        PresenceConnection(peer1: $0.peer1, peer2: $0.peer2, transport: $0.type.rawValue)
      }
    )
    outboxVisibility.update(presentPeerKeys) { [self] removedPeers in
      withState { state in
        state.visiblePeers = presentPeerKeys
        for peer in removedPeers {
          state.outboxTasks.removeValue(forKey: peer)?.cancel()
          state.outboxes.removeValue(forKey: peer)?.close()
          state.peerSendMutexes.removeValue(forKey: peer)
        }
      }
    }
    let outstandingInitialSync = withState { state -> Bool in
      let visible = state.visiblePeers
      cancelTasks(&state.connectTasks) { !visible.contains($0.peerKey) }
      for key in state.streams.keys.filter({ !visible.contains($0.peerKey) }) {
        state.streams.removeValue(forKey: key)?.close()
      }
      // Tear down outboxes and partial-transfer state for peers that left, so their drain loops
      // don't spin forever and their bounded channels can never wedge a producer.
      for key in state.chunkAssemblies.keys.filter({ !visible.contains($0.peer) }) {
        state.chunkAssemblies.removeValue(forKey: key)?.timeoutTask?.cancel()
      }
      for peer in state.hydrations.keys.filter({ !visible.contains($0) }) {
        let hydration = state.hydrations.removeValue(forKey: peer)
        hydration?.timeoutTask?.cancel()
        hydration?.finishTask?.cancel()
      }
      cancelTasks(&state.snapshotTasks) { !visible.contains($0) }
      cancelTasks(&state.reciprocalSnapshotTasks) { !visible.contains($0) }
      cancelTasks(&state.snapshotRetryTasks) { !visible.contains($0) }
      cancelTasks(&state.hydrationSlotWaitTasks) { !visible.contains($0) }
      cancelTasks(&state.queuedSnapshotTasks) { !visible.contains($0) }
      state.snapshotRetryAttempts.retainPeers(visible)
      cancelTasks(&state.outboundSnapshotRetryTasks) { !visible.contains($0) }
      state.outboundSnapshotRetryAttempts.retainPeers(visible)
      state.snapshotRequested = state.snapshotRequested.filter { visible.contains($0) }
      state.lastHelloNanos = state.lastHelloNanos.filter { visible.contains($0.key) }
      state.pendingHellos = state.pendingHellos.filter { visible.contains($0.key) }
      state.lastRemoteDigests = state.lastRemoteDigests.filter { visible.contains($0.key) }
      cancelTasks(&state.helloCoalesceTasks) { !visible.contains($0) }
      for peer in state.outboundSnapshots.keys.filter({ !visible.contains($0) }) {
        state.outboundSnapshots.removeValue(forKey: peer)?.timeoutTask?.cancel()
      }
      state.incompatiblePeerKeys = state.incompatiblePeerKeys.filter { visible.contains($0) }
      state.synchronizedPeerKeys = state.synchronizedPeerKeys.filter { visible.contains($0) }
      state.awaitingInitialSyncPeerKeys = state.awaitingInitialSyncPeerKeys.filter { visible.contains($0) }
      state.readinessWaivedPeerKeys = state.readinessWaivedPeerKeys.filter { visible.contains($0) }
      let outstanding = hasOutstandingInitialSync(
        visiblePeers: visible,
        awaitingPeers: state.awaitingInitialSyncPeerKeys,
        waivedPeers: state.readinessWaivedPeerKeys
      )
      cancelTasks(&state.recoveryTasks) { !visible.contains($0.peerKey) }
      operationDispatcher.retainPeers(visible)
      snapshotControlDispatcher.retainPeers(visible)
      // Drop traffic counters for departed peers too, otherwise the 1s rate worker re-inserts a
      // ghost PeerDiagnostics for every peer that ever sent or received traffic.
      for peer in Set(state.txCounters.keys).union(state.rxCounters.keys) where !visible.contains(peer) {
        state.txCounters.removeValue(forKey: peer)
        state.rxCounters.removeValue(forKey: peer)
      }
      return outstanding
    }
    for peer in remote {
      let newGateStarted = withState { state -> Bool in
        let synchronized = state.synchronizedPeerKeys
        let waived = state.readinessWaivedPeerKeys
        let added = tryAwaitInitialSync(
          peer: peer.peerKey,
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
      if newGateStarted {
        // Presence is continuous, not a one-shot startup list. A peer first discovered after the
        // original readiness window must gate editing until its digest is reconciled too.
        updateDiagnostics { current in
          var next = current
          next.connectivityMessage = connectivityMessageAfterNewInitialSyncGate(
            currentMessage: current.connectivityMessage,
            newGateStarted: true
          )
          return next
        }
      }
      let connectionNames = directPresenceTransports(
        localPeerKey: localPeerKey,
        remotePeerKey: peer.peerKey,
        connections: connections
      )
      let knownProfile = knownLog.profile(peer.peerKey)
      let displayName = knownProfile?.displayName
        ?? safeDisplayName(metadataString(peer.peerMetadata["displayName"]) ?? peer.deviceName)
      let colorArgb = knownProfile?.colorArgb ?? metadataInt(peer.peerMetadata["colorArgb"]).map { Int32($0) }
      updatePeer(peer.peerKey) { entry in
        entry.displayName = displayName
        entry.colorArgb = colorArgb
        entry.transports = connectionNames
      }
      if localPeerKey < peer.peerKey {
        ensureConnectJob(
          StreamKey(peerKey: peer.peerKey, topic: WhiteboardProtocol.liveStreamName),
          reliability: .unreliable
        )
        ensureConnectJob(
          StreamKey(peerKey: peer.peerKey, topic: WhiteboardProtocol.stateStreamName),
          reliability: .reliable
        )
      }
    }
    refreshEditingReadiness()
    updateDiagnostics { current in
      var next = current
      next.peers = next.peers.filter { presentPeerKeys.contains($0.key) }
      next.incompatiblePeers = next.incompatiblePeers.filter { presentPeerKeys.contains($0.key) }
      next.incompatiblePeers.merge(metadataIncompatible) { _, new in new }
      next.presenceConnections = connections
      if capacityLimited {
        next.connectivityMessage =
          "Nearby board capacity is \(maxConnectedPeers) peers; additional peers are ignored."
      } else if current.connectivityMessage?.hasPrefix("Nearby board capacity") == true {
        next.connectivityMessage = nil
      } else if !outstandingInitialSync
        && current.connectivityMessage?.hasPrefix("Initial nearby sync timed out") == true
      {
        next.connectivityMessage = nil
      }
      return next
    }
  }

  // MARK: - Connecting

  func ensureConnectJob(_ key: StreamKey, reliability: DittoReliability) {
    withState { state in
      guard isNearbyLifecycleActive(
        started: state.started,
        foreground: state.foreground,
        nearbyNetworkingRunning: state.nearbyNetworkingRunning,
        localOnly: state.localOnly
      ),
        !state.incompatiblePeerKeys.contains(key.peerKey),
        state.streams[key] == nil,
        state.connectTasks[key] == nil
      else { return }
      state.connectTasks[key] = spawn { [self] in
        await connectLoop(key: key, reliability: reliability)
      }
    }
  }

  private func connectLoop(key: StreamKey, reliability: DittoReliability) async {
    defer { withState { $0.connectTasks.removeValue(forKey: key) } }
    var backoff: Int64 = 500
    while !Task.isCancelled,
          nearbyLifecycleActive(),
          withState({ $0.visiblePeers.contains(key.peerKey) }),
          !withState({ $0.incompatiblePeerKeys.contains(key.peerKey) }),
          withState({ $0.streams[key] == nil })
    {
      do {
        let stream = try await ditto.dataStreams.connect(
          peerKey: key.peerKey,
          topic: key.topic,
          requestCompression: reliability == .reliable,
          reliability: reliability,
          timeoutMs: 10_000
        ) { candidate in
          try candidate.open { [self] inbound in
            if let payload = try? inbound.payload {
              enqueueInbound(peer: key.peerKey, topic: key.topic, payload: payload)
            }
          }
        }
        backoff = 500
        await registerAndWait(key: key, stream: stream)
      } catch is CancellationError {
        return
      } catch {
        // An in-flight cancellation can surface as a DataStreams error; confirm the task is still
        // active before treating this as a retryable connection failure. (See SKILL.md.)
        if Task.isCancelled { return }
        recordError(key.peerKey, error)
      }
      if nearbyLifecycleActive(),
         withState({ $0.visiblePeers.contains(key.peerKey) }),
         withState({ $0.streams[key] == nil })
      {
        let jitterBound = min(250, backoff / 4)
        let jitter = Int64.random(in: 0...jitterBound)
        await sleep(milliseconds: backoff + jitter)
        backoff = min(backoff * 2, 10_000)
      }
    }
  }

  func registerAndWait(key: StreamKey, stream: DittoStream) async {
    let registered = await lifecycleMutex.withLock {
      withState { state -> Bool in
        guard isNearbyLifecycleActive(
          started: state.started,
          foreground: state.foreground,
          nearbyNetworkingRunning: state.nearbyNetworkingRunning,
          localOnly: state.localOnly
        ), state.visiblePeers.contains(key.peerKey) else { return false }
        if let old = state.streams.updateValue(stream, forKey: key), old !== stream {
          old.close()
        }
        return true
      }
    }
    guard registered else {
      stream.close()
      return
    }
    if key.topic == WhiteboardProtocol.stateStreamName {
      let newGateStarted = withState { state -> Bool in
        guard !state.initialReadinessResolved else { return false }
        let synchronized = state.synchronizedPeerKeys
        let waived = state.readinessWaivedPeerKeys
        let added = tryAwaitInitialSync(
          peer: key.peerKey,
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
      clearSnapshotRetry(key.peerKey)
      withState { $0.lastHelloNanos.removeValue(forKey: key.peerKey) }
      _ = outbox(key.peerKey)
      await sendHello(key.peerKey)
    }
    refreshConnectionStatus(key.peerKey)
    _ = try? await stream.waitUntilClosed()
    withState { state in
      if state.streams[key] === stream {
        state.streams.removeValue(forKey: key)
      }
    }
    refreshConnectionStatus(key.peerKey)
  }

  // MARK: - Inbound plumbing

  /// Called from Ditto driver threads; must stay fast and never block.
  func enqueueInbound(peer: String, topic: String, payload: Data) {
    incrementRx(peer)
    let limit = topic == WhiteboardProtocol.liveStreamName
      ? WhiteboardProtocol.maxLiveFrameBytes
      : WhiteboardProtocol.maxReliableFrameBytes
    if payload.count > limit {
      recoverStream(peer, topic, "Inbound \(topic) frame exceeds the protocol limit")
      return
    }
    let frame = InboundFrame(peer: peer, payload: payload)
    let accepted = topic == WhiteboardProtocol.liveStreamName
      ? liveInbound.trySend(frame)
      : reliableInbound.trySend(frame)
    if !accepted && topic == WhiteboardProtocol.stateStreamName {
      recoverStream(peer, topic, "Reliable ingress saturated; reconnecting for snapshot repair")
    }
  }

  private func startWorkers() {
    spawn { [self] in
      // Guard every frame: a single malformed reliable payload must never complete this consumer
      // (which would permanently halt reliable sync) or escape the task.
      for await frame in reliableInbound {
        handleReliable(peer: frame.peer, payload: frame.payload)
      }
    }
    spawn { [self] in
      for await frame in liveInbound {
        if withState({ $0.hydrations[frame.peer] }) != nil { continue }
        switch WhiteboardProtocol.decodeLive(
          frame.payload,
          nowMillis: currentTimeMillis(),
          expectedPeerKey: frame.peer
        ) {
        case .compatible(let preview):
          eventsBroadcast.yield(.livePreviewReceived(preview))
        case .incompatible(let version):
          incompatible(frame.peer, version: version)
        case .invalid(let reason):
          recordError(frame.peer, reason)
        }
      }
    }
    spawn { [self] in
      try? await dispatchLiveFrames(
        frames: pendingLive,
        encode: { preview in
          try WhiteboardProtocol.encodeLive(
            preview,
            sequence: nextLiveSequence(),
            senderSessionId: liveSessionId
          )
        },
        recipients: {
          withState { state in
            state.streams
              .filter { $0.key.topic == WhiteboardProtocol.liveStreamName }
              .map { ($0.key, $0.value) }
          }
        },
        maxSendSize: { recipient in Int64(try recipient.1.maxSendSize) },
        send: { recipient, bytes in try recipient.1.sendAndForget(bytes) },
        onSent: { [self] recipient in incrementTx(recipient.0.peerKey) },
        onFailure: { [self] recipient, error in
          if let recipient { recordError(recipient.0.peerKey, error) }
        },
        throttle: { await sleep(milliseconds: 34) }
      )
    }
    spawn { [self] in
      while !Task.isCancelled {
        await sleep(milliseconds: 1_000)
        guard !Task.isCancelled else { return }
        let peers = withState { Set($0.txCounters.keys).union($0.rxCounters.keys) }
        for peer in peers {
          // Never resurrect a departed peer: drop counters that raced the presence prune instead
          // of re-inserting a ghost diagnostics entry.
          let rates = withState { state -> (Double, Double)? in
            guard state.visiblePeers.contains(peer) else {
              state.txCounters.removeValue(forKey: peer)
              state.rxCounters.removeValue(forKey: peer)
              return nil
            }
            let tx = state.txCounters.removeValue(forKey: peer) ?? 0
            let rx = state.rxCounters.removeValue(forKey: peer) ?? 0
            if state.txCounters[peer] == nil { state.txCounters[peer] = 0 }
            if state.rxCounters[peer] == nil { state.rxCounters[peer] = 0 }
            return (Double(tx), Double(rx))
          }
          guard let rates else { continue }
          updatePeer(peer) { entry in
            entry.transmitMessagesPerSecond = rates.0
            entry.receiveMessagesPerSecond = rates.1
          }
        }
      }
    }
  }

  // MARK: - Readiness

  func refreshEditingReadiness() {
    let editingReady = withState { state -> Bool in
      if state.localOnly
        || (state.ready && !state.awaitingInitialSyncPeerKeys.contains(where: { state.visiblePeers.contains($0) }))
      {
        state.initialReadinessResolved = true
        state.initialSyncTimeoutTask?.cancel()
        state.initialSyncTimeoutTask = nil
        state.readinessGeneration += 1
      } else if shouldStartInitialSyncDeadline(
        ready: state.ready,
        initialReadinessResolved: state.initialReadinessResolved,
        timeoutActive: state.initialSyncTimeoutTask != nil
      ) {
        state.readinessGeneration += 1
        let generation = state.readinessGeneration
        state.initialSyncTimeoutTask = spawn { [self] in
          await sleep(milliseconds: initialSyncTimeoutMillis)
          guard !Task.isCancelled else { return }
          let waived = withState { state -> Bool in
            guard !state.initialReadinessResolved, state.readinessGeneration == generation else {
              return false
            }
            state.initialReadinessResolved = true
            state.readinessWaivedPeerKeys.formUnion(state.awaitingInitialSyncPeerKeys)
            state.awaitingInitialSyncPeerKeys.removeAll()
            state.initialSyncTimeoutTask = nil
            return true
          }
          if waived {
            updateDiagnostics { current in
              var next = current
              next.editingReady = true
              next.connectivityMessage =
                "Initial nearby sync timed out. Editing is available, but an unreachable peer may be stale."
              return next
            }
          }
        }
      }
      return state.localOnly || state.initialReadinessResolved
    }
    updateDiagnostics { current in
      var next = current
      next.editingReady = editingReady
      return next
    }
  }

  func markInitialSyncComplete(_ peer: String) {
    withState { state in
      state.synchronizedPeerKeys.insert(peer)
      state.readinessWaivedPeerKeys.remove(peer)
      state.awaitingInitialSyncPeerKeys.remove(peer)
    }
    clearInitialSyncTimeoutIfNoOutstanding()
    refreshEditingReadiness()
  }

  func clearInitialSyncTimeoutIfNoOutstanding() {
    let clear = withState { state in
      !hasOutstandingInitialSync(
        visiblePeers: state.visiblePeers,
        awaitingPeers: state.awaitingInitialSyncPeerKeys,
        waivedPeers: state.readinessWaivedPeerKeys
      )
    }
    guard clear else { return }
    updateDiagnostics { current in
      guard current.connectivityMessage?.hasPrefix("Initial nearby sync timed out") == true else {
        return current
      }
      var next = current
      next.connectivityMessage = nil
      return next
    }
  }

  // MARK: - Helpers

  @discardableResult
  func spawn(_ body: @escaping @Sendable () async -> Void) -> Task<Void, Never> {
    taskRegistry.spawn(body)
  }

  func nextControlSequence() -> Int64 {
    sequenceLock.withLock {
      controlSequence += 1
      return controlSequence
    }
  }

  private func nextLiveSequence() -> Int64 {
    sequenceLock.withLock {
      liveSequence += 1
      return liveSequence
    }
  }

  func incrementTx(_ peer: String) {
    withState { $0.txCounters[peer, default: 0] += 1 }
  }

  func incrementRx(_ peer: String) {
    withState { $0.rxCounters[peer, default: 0] += 1 }
  }

  func nearbyLifecycleActive() -> Bool {
    withState { state in
      isNearbyLifecycleActive(
        started: state.started,
        foreground: state.foreground,
        nearbyNetworkingRunning: state.nearbyNetworkingRunning,
        localOnly: state.localOnly
      )
    }
  }

  func updateDiagnostics(_ transform: (TransportDiagnostics) -> TransportDiagnostics) {
    let next = diagnosticsLock.withLock { () -> TransportDiagnostics in
      let updated = transform(diagnosticsValue)
      if updated != diagnosticsValue { diagnosticsValue = updated }
      return diagnosticsValue
    }
    if next != diagnosticsBroadcast.latestSnapshot {
      diagnosticsBroadcast.yield(next)
    }
  }

  func updatePeer(_ peer: String, _ update: (inout PeerDiagnostics) -> Void) {
    updateDiagnostics { current in
      var next = current
      var entry = next.peers[peer] ?? PeerDiagnostics(peerKey: peer)
      update(&entry)
      next.peers[peer] = entry
      return next
    }
  }

  func refreshConnectionStatus(_ peer: String) {
    let status = withState { state in
      (
        state.streams[StreamKey(peerKey: peer, topic: WhiteboardProtocol.liveStreamName)] != nil,
        state.streams[StreamKey(peerKey: peer, topic: WhiteboardProtocol.stateStreamName)] != nil
      )
    }
    updatePeer(peer) { entry in
      entry.liveConnected = status.0
      entry.stateConnected = status.1
    }
  }

  func recordError(_ peer: String, _ error: any Error) {
    updatePeer(peer) { $0.lastError = safeDiagnostic(error.localizedDescription) }
  }

  func recordError(_ peer: String, _ message: String) {
    updatePeer(peer) { $0.lastError = safeDiagnostic(message) }
  }

  func traceReadiness(_ message: String) {
    #if DEBUG
    FileHandle.standardError.write("DittoWhiteboard: \(message)\n".data(using: .utf8) ?? Data())
    #endif
  }

  func safeDiagnostic(_ message: String) -> String {
    let prefixed = String(message.prefix(maxDiagnosticLength * 2))
    let filtered = String(
      String.UnicodeScalarView(
        prefixed.unicodeScalars.filter {
          $0 == "\n" || !($0.value <= 0x1F || (0x7F...0x9F).contains($0.value))
        }
      )
    )
    return String(filtered.prefix(maxDiagnosticLength))
  }

  func safeDisplayName(_ value: String?) -> String? {
    guard let value else { return nil }
    let stripped = String(
      String.UnicodeScalarView(
        value.unicodeScalars.filter { !($0.value <= 0x1F || (0x7F...0x9F).contains($0.value)) }
      )
    )
    let trimmed = stripped.trimmingCharacters(in: .whitespaces)
    let taken = String(trimmed.prefix(24))
    return taken.isEmpty ? nil : taken
  }

  func incompatible(_ peer: String, version: Int) {
    withState { state in
      state.incompatiblePeerKeys.insert(peer)
      state.synchronizedPeerKeys.remove(peer)
      state.awaitingInitialSyncPeerKeys.remove(peer)
      state.readinessWaivedPeerKeys.remove(peer)
    }
    clearInitialSyncTimeoutIfNoOutstanding()
    refreshEditingReadiness()
    updateDiagnostics { current in
      var next = current
      next.incompatiblePeers[peer] = version
      return next
    }
    eventsBroadcast.yield(.incompatiblePeer(peerKey: peer, protocolVersion: version))
    let doomed = withState { state -> [DittoStream] in
      var closed: [DittoStream] = []
      for key in state.streams.keys.filter({ $0.peerKey == peer }) {
        if let stream = state.streams.removeValue(forKey: key) { closed.append(stream) }
      }
      return closed
    }
    for stream in doomed { stream.close() }
  }
}

/// Thread-safe owner of the authoritative reconciliation log, mirroring the Kotlin
/// `BoundedOperationLog`'s synchronized methods.
final class KnownLogBox: @unchecked Sendable {
  private let lock = NSLock()
  private var log = BoundedOperationLog()

  func accept(_ operation: BoardOperation) -> KnownOperationResult {
    lock.withLock { log.accept(operation) }
  }

  func merge(_ operations: [BoardOperation]) -> String? {
    lock.withLock { log.merge(operations) }
  }

  func material() -> KnownStateMaterial {
    lock.withLock { log.material() }
  }

  func capture() -> KnownStateCapture {
    lock.withLock { log.capture() }
  }

  func isCurrent(_ version: Int64) -> Bool {
    lock.withLock { log.isCurrent(version) }
  }

  func profile(_ peerKey: String) -> UserProfile? {
    lock.withLock { log.profile(peerKey) }
  }
}
