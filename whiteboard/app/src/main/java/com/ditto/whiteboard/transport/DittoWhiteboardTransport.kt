package com.ditto.whiteboard.transport

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.ditto.kotlin.Ditto
import com.ditto.kotlin.DittoAcceptor
import com.ditto.kotlin.DittoConfig
import com.ditto.kotlin.DittoException
import com.ditto.kotlin.DittoFactory
import com.ditto.kotlin.DittoReliability
import com.ditto.kotlin.DittoSendStatus
import com.ditto.kotlin.DittoStream
import com.ditto.kotlin.PreviewDataStreams
import com.ditto.kotlin.open
import com.ditto.kotlin.transports.DittoSyncPermissions
import com.ditto.whiteboard.domain.BOARD_ID
import com.ditto.whiteboard.domain.BoardOperation
import com.ditto.whiteboard.domain.BoardState
import com.ditto.whiteboard.domain.LivePreview
import com.ditto.whiteboard.domain.OperationId
import com.ditto.whiteboard.domain.UserProfile
import com.ditto.whiteboard.domain.canonicalOperation
import com.ditto.whiteboard.BuildConfig
import com.ditto.whiteboard.protocol.LIVE_STREAM_NAME
import com.ditto.whiteboard.protocol.MAX_LIVE_FRAME_BYTES
import com.ditto.whiteboard.protocol.MAX_RELIABLE_FRAME_BYTES
import com.ditto.whiteboard.protocol.MAX_SESSION_LAMPORT
import com.ditto.whiteboard.protocol.MAX_TRANSFER_BYTES
import com.ditto.whiteboard.protocol.MAX_TRANSFER_CHUNKS
import com.ditto.whiteboard.protocol.MAX_TRANSFER_ID_LENGTH
import com.ditto.whiteboard.protocol.PROTOCOL_VERSION
import com.ditto.whiteboard.protocol.ProtocolDecodeResult
import com.ditto.whiteboard.protocol.SHA_256_BYTE_COUNT
import com.ditto.whiteboard.protocol.STATE_STREAM_NAME
import com.ditto.whiteboard.protocol.SnapshotAssembler
import com.ditto.whiteboard.protocol.SnapshotAssemblyResult
import com.ditto.whiteboard.protocol.SnapshotTransfer
import com.ditto.whiteboard.protocol.WhiteboardProtocol
import com.ditto.whiteboard.protocol.shouldOfferSnapshot
import com.ditto.whiteboard.protocol.shouldSendReciprocalSnapshot
import com.ditto.whiteboard.util.runCatchingException
import com.ditto.whiteboard.protocol.proto.Envelope
import com.ditto.whiteboard.protocol.proto.ReliableChunk
import com.ditto.whiteboard.protocol.proto.SnapshotAck
import com.ditto.whiteboard.protocol.proto.SnapshotBegin
import com.ditto.whiteboard.protocol.proto.SnapshotChunk
import com.ditto.whiteboard.protocol.proto.SnapshotEnd
import com.google.protobuf.ByteString
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val RELIABLE_OUTBOX_CAPACITY = 64
private const val RELIABLE_INBOUND_CAPACITY = 64
private const val OPERATION_APPLICATION_CAPACITY_PER_PEER = 32
private const val MAX_BUFFERED_HYDRATION_OPERATIONS = 64
private const val MAX_BUFFERED_HYDRATION_BYTES = 2 * 1024 * 1024
private const val MAX_CHUNK_ASSEMBLIES_PER_PEER = 4
private const val RELIABLE_TRANSFER_TIMEOUT_MILLIS = 10_000L
private const val MAX_RELIABLE_TRANSFER_LIFETIME_MILLIS = 60_000L
private const val TRANSFER_INACTIVITY_TIMEOUT_MILLIS = 30_000L
private const val MAX_SNAPSHOT_TRANSFER_LIFETIME_MILLIS = 5 * 60_000L
private const val MAX_SNAPSHOT_RETRIES = 3
private const val HELLO_MIN_INTERVAL_NANOS = 250_000_000L
private const val INITIAL_SYNC_TIMEOUT_MILLIS = 30_000L
private const val MAX_DIAGNOSTIC_LENGTH = 256

/** Give up re-sending a single reliable frame after this many stream failures and drop it. */
private const val MAX_SEND_ATTEMPTS = 5

/**
 * Give up waiting for a (re)connect after this many 100ms polls for a peer that stays visible in
 * presence but has no wb_state stream, dropping the frame. This bounds how long such a peer can hold
 * its bounded outbox full: without it the drain loop waits forever, the channel wedges at capacity,
 * and every producer that fans out to it stalls. A dropped frame re-heals from the next snapshot
 * exchange once the peer's stream comes back.
 */
private const val MAX_STREAMLESS_WAITS = 50

private data class StreamKey(val peerKey: String, val topic: String)

private fun ByteArray.toHex(): String = buildString(size * 2) {
  this@toHex.forEach { append("0123456789abcdef"[(it.toInt() shr 4) and 0xF]).append("0123456789abcdef"[it.toInt() and 0xF]) }
}

private data class SnapshotHydration(
  val transferId: String,
  val assembler: SnapshotAssembler,
  val chunkCount: Int,
  val receivedChunks: MutableSet<Int> = mutableSetOf(),
  val bufferedOperations: MutableMap<OperationId, BoardOperation> = linkedMapOf(),
  var bufferedOperationBytes: Int = 0,
  val startedAtNanos: Long = System.nanoTime(),
  var timeoutJob: Job? = null,
  var finishJob: Job? = null,
  var finishing: Boolean = false,
)

private data class ChunkAssembly(
  val assembler: SnapshotAssembler,
  val received: MutableSet<Int> = mutableSetOf(),
  val expectedCount: Int,
  val expectedByteCount: Long,
  val expectedDigest: ByteArray,
  val startedAtNanos: Long = System.nanoTime(),
  var timeoutJob: Job? = null,
)

private data class SnapshotMaterial(
  val version: Long,
  val state: BoardState,
  val bytes: ByteArray,
  val byteDigest: ByteArray,
)

private data class OutboundSnapshot(
  val transferId: String,
  var timeoutJob: Job? = null,
)

/**
 * The real [WhiteboardTransport], and the heart of this Data Streams demo.
 *
 * It binds two Data Streams topics on every peer and mirrors board traffic across the nearby mesh:
 *
 * - **`wb_live`** (Unreliable) carries fire-and-forget drawing previews — low latency, loss is fine.
 * - **`wb_state`** (Reliable) carries the operations that define the board (commits, erases, clears,
 *   profiles) plus late-join snapshots — ordered transport with bounded application queues and
 *   digest/snapshot repair after saturation, reconnect, or process failure.
 *
 * Connection ownership is driven by presence: to avoid opening a stream in both directions, the peer
 * with the lower public key calls [connect][com.ditto.kotlin.DittoDataStreamsEndpoint.connect] while
 * the higher-key peer only accepts via `bindTopic`. Inbound callbacks retain the payload supplied by
 * the SDK and hand it to a channel so the driver thread is never blocked. Reconnection uses bounded
 * exponential
 * backoff with jitter. Nothing is written to the Ditto store — the board is entirely ephemeral.
 */
@OptIn(PreviewDataStreams::class)
class DittoWhiteboardTransport(
  context: Context,
  databaseId: String,
  offlineLicenseToken: String,
  parentScope: CoroutineScope,
  // Injected so tests can pin work onto a controllable dispatcher instead of the real IO pool.
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : WhiteboardTransport {
  private val transportJob = SupervisorJob(parentScope.coroutineContext[Job])
  private val scope = CoroutineScope(parentScope.coroutineContext + transportJob + CoroutineName("DittoWhiteboard"))
  private val ditto: Ditto = DittoFactory.create(
    DittoConfig(databaseId = databaseId, connect = DittoConfig.Connect.SmallPeersOnly()),
    scope,
  ).apply { setOfflineOnlyLicenseToken(offlineLicenseToken) }

  override val localPeerKey: String = ditto.presence.graph.localPeer.peerKey
  override val requiredPermissions: List<String> = DittoSyncPermissions(context).requiredPermissions()
  private val permissionReady = CompletableDeferred<Unit>()
  private val mutableEvents = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 64)
  override val events = mutableEvents.asSharedFlow()
  private val diagnosticsState = DiagnosticsState(
    TransportDiagnostics(
      localPeerKey = localPeerKey,
      editingReady = false,
      mode = TransportMode.NearbyMesh,
    ),
  )
  override val diagnostics = diagnosticsState.flow
  private val acceptors = mutableListOf<DittoAcceptor>()
  private val streams = ConcurrentHashMap<StreamKey, DittoStream>()
  private val connectJobs = ConcurrentHashMap<StreamKey, Job>()
  private val acceptOpenJobs = ConcurrentHashMap.newKeySet<Job>()
  private val outboxes = ConcurrentHashMap<String, Channel<ByteArray>>()
  private val outboxJobs = ConcurrentHashMap<String, Job>()
  private val outboxVisibility = PeerVisibilityGate()
  @Volatile private var presenceJob: Job? = null
  @Volatile private var presenceRecovering = false
  private val peerSendMutexes = ConcurrentHashMap<String, Mutex>()
  private val reliableInbound = Channel<Pair<String, ByteArray>>(RELIABLE_INBOUND_CAPACITY)
  private val liveInbound = Channel<Pair<String, ByteArray>>(128, BufferOverflow.DROP_OLDEST)
  private val pendingLive = Channel<LivePreview>(1, BufferOverflow.DROP_OLDEST)
  private val operationDispatcher = PerPeerOperationDispatcher<BoardOperation>(
    scope = scope,
    capacityPerPeer = OPERATION_APPLICATION_CAPACITY_PER_PEER,
  ) { peer, operation ->
    try {
      handleOperation(peer, operation)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      recordError(peer, error)
      recoverStream(peer, STATE_STREAM_NAME, "Reliable operation application failed")
    }
  }
  private val hydrations = ConcurrentHashMap<String, SnapshotHydration>()
  private val hydrationLock = Any()
  // At most one outbound snapshot transfer per peer may be in flight, so two overlapping hellos
  // can't launch concurrent sends that interleave BEGIN/CHUNK/END frames on the peer's single
  // ordered outbox and corrupt (or mutually reject) each other's transfer.
  private val snapshotJobs = ConcurrentHashMap<String, Job>()
  private val reciprocalSnapshotJobs = ConcurrentHashMap<String, Job>()
  private val chunkAssemblies = ConcurrentHashMap<Pair<String, String>, ChunkAssembly>()
  private val recoveryJobs = ConcurrentHashMap<StreamKey, Job>()
  private val snapshotRetryJobs = ConcurrentHashMap<String, Job>()
  private val snapshotRetryAttempts = ConcurrentHashMap<String, AtomicInteger>()
  private val outboundSnapshotRetryJobs = ConcurrentHashMap<String, Job>()
  private val outboundSnapshotRetryAttempts = ConcurrentHashMap<String, AtomicInteger>()
  private val lastHelloNanos = ConcurrentHashMap<String, Long>()
  private val pendingHellos = ConcurrentHashMap<String, Envelope>()
  private val helloCoalesceJobs = ConcurrentHashMap<String, Job>()
  private val outboundSnapshots = ConcurrentHashMap<String, OutboundSnapshot>()
  private val snapshotRequested = ConcurrentHashMap.newKeySet<String>()
  private val incompatiblePeerKeys = ConcurrentHashMap.newKeySet<String>()
  private val synchronizedPeerKeys = ConcurrentHashMap.newKeySet<String>()
  private val awaitingInitialSyncPeerKeys = ConcurrentHashMap.newKeySet<String>()
  private val readinessWaivedPeerKeys = ConcurrentHashMap.newKeySet<String>()
  private val readinessLock = Any()
  @Volatile private var initialReadinessResolved = false
  @Volatile private var initialSyncTimeoutJob: Job? = null
  @Volatile private var readinessDelayJob: Job? = null
  private val readinessGeneration = AtomicLong()
  private val txCounters = ConcurrentHashMap<String, AtomicLong>()
  private val rxCounters = ConcurrentHashMap<String, AtomicLong>()
  private val controlSequence = AtomicLong()
  private val liveSequence = AtomicLong()
  private val liveSessionId = UUID.randomUUID().toString()
  private val knownLog = BoundedOperationLog()
  private val snapshotMaterialMutex = Mutex()
  private val lifecycleMutex = Mutex()
  private val foregroundGeneration = AtomicLong()
  private var cachedSnapshotMaterial: SnapshotMaterial? = null
  // Read from IO connect/outbox coroutines while written from the presence/start coroutines,
  // so both need @Volatile to establish a happens-before edge across threads.
  @Volatile private var profile: UserProfile? = null
  @Volatile private var visiblePeers: Set<String> = emptySet()
  @Volatile private var started = false
  @Volatile private var ready = false
  @Volatile private var localOnly = false
  @Volatile private var foreground = true
  @Volatile private var nearbyNetworkingRunning = false
  private val lifecycleCoordinator: TransportLifecycleCoordinator

  init {
    val alreadyGranted = requiredPermissions.all {
      ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    lifecycleCoordinator = TransportLifecycleCoordinator(
      permissionsGranted = alreadyGranted,
      requestedForeground = true,
    )
    if (alreadyGranted) permissionReady.complete(Unit)
    configureTransports(context)
    startWorkers()
  }

  override fun resolvePermissions(allGranted: Boolean) {
    val completesInitialDecision = !permissionReady.isCompleted
    val generation = lifecycleCoordinator.requestPermissions(allGranted)
    scope.launch(ioDispatcher) {
      try {
        lifecycleMutex.withLock {
          if (!lifecycleCoordinator.isCurrent(generation)) return@withLock
          ditto.refreshPermissions()
          val target = lifecycleCoordinator.markPermissionsRefreshed(generation)
            ?: return@withLock
          localOnly = !target.permissionsGranted
          diagnosticsState.update {
            if (localOnly) {
              it.copy(
                editingReady = true,
                mode = TransportMode.LocalPreview,
                connectivityMessage = "Nearby collaboration is off for this session.",
              )
            } else {
              it.copy(
                mode = TransportMode.NearbyMesh,
                connectivityMessage = null,
              )
            }
          }
          reconcileLifecycleTarget(target)
          if (completesInitialDecision && !permissionReady.isCompleted) {
            permissionReady.complete(Unit)
          }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        lifecycleMutex.withLock {
          if (lifecycleCoordinator.isCurrent(generation)) {
            localOnly = true
            if (nearbyNetworkingRunning) pauseStartedNetworking()
            foreground = lifecycleCoordinator.snapshot().requestedForeground
            diagnosticsState.update {
              it.copy(
                running = started && foreground,
                editingReady = true,
                mode = TransportMode.LocalPreview,
                connectivityMessage = safeDiagnostic(
                  "Could not refresh nearby permissions: " +
                    (error.message ?: error::class.java.simpleName),
                ),
              )
            }
            if (completesInitialDecision && !permissionReady.isCompleted) {
              permissionReady.complete(Unit)
            }
          }
        }
      }
    }
  }

  override suspend fun start(profile: UserProfile) {
    permissionReady.await()
    withContext(ioDispatcher) {
      lifecycleMutex.withLock {
        this@DittoWhiteboardTransport.profile = profile
        val target = lifecycleCoordinator.snapshot()
        if (started) {
          reconcileLifecycleTarget(target)
          return@withLock
        }
        foreground = target.requestedForeground
        localOnly = !target.permissionsGranted || !target.permissionsReadyForSync
        if (localOnly) {
          started = true
          diagnosticsState.update {
            it.copy(
              running = foreground,
              editingReady = true,
              mode = TransportMode.LocalPreview,
              connectivityMessage = "Nearby collaboration is off for this session.",
            )
          }
          return@withLock
        }
        started = true
        reconcileLifecycleTarget(target)
      }
    }
  }

  override suspend fun sendReliable(operation: BoardOperation): Boolean {
    if (acceptKnownOperation(operation) is KnownOperationResult.Rejected) return false
    // Local fallback edits remain in the authoritative digest/log so a later permission grant can
    // offer them in reconciliation. Only radio fan-out is skipped while localOnly.
    if (localOnly) return true
    val bytes = WhiteboardProtocol.operationEnvelope(operation)
    // Never create one potentially-suspended coroutine per operation and peer. A saturated peer
    // drops this enqueue, closes its state stream, and reconciles from the next Hello/snapshot.
    // Healthy peers remain independent, while memory stays bounded under sustained topology churn.
    streams.keys.asSequence().filter { it.topic == STATE_STREAM_NAME }.map(StreamKey::peerKey).distinct().forEach { peer ->
      if (!tryEnqueueOutbox(peer, bytes)) {
        recoverStream(peer, STATE_STREAM_NAME, "Reliable outbox saturated; reconciling from snapshot")
      }
    }
    return true
  }

  override fun sendLive(preview: LivePreview) {
    if (localOnly) return
    pendingLive.trySend(preview.copy(points = preview.points.takeLast(32)))
  }

  override fun setForeground(isForeground: Boolean) {
    if (!lifecycleCoordinator.requestForeground(isForeground)) return
    val generation = foregroundGeneration.incrementAndGet()
    scope.launch(ioDispatcher) {
      try {
        lifecycleMutex.withLock {
          if (generation != foregroundGeneration.get()) return@withLock
          reconcileLifecycleTarget(lifecycleCoordinator.snapshot())
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        diagnosticsState.update { current ->
          current.copy(
            running = false,
            connectivityMessage = safeDiagnostic(
              "Nearby lifecycle update failed: ${error.message ?: error::class.java.simpleName}",
            ),
          )
        }
      }
    }
  }

  private fun reconcileLifecycleTarget(
    target: TransportLifecycleCoordinator.Snapshot,
  ) {
    foreground = target.requestedForeground
    if (!started) return
    if (localOnly) {
      if (nearbyNetworkingRunning) pauseStartedNetworking()
      diagnosticsState.update { it.copy(running = foreground, editingReady = true) }
      return
    }
    if (!target.permissionsReadyForSync) {
      if (nearbyNetworkingRunning) pauseStartedNetworking()
      diagnosticsState.update { it.copy(running = false) }
      return
    }
    profile?.let(::prepareNearbyNetworking)
    if (target.shouldRunNearby(started = started, localOnly = localOnly)) {
      if (nearbyNetworkingRunning) {
        diagnosticsState.update { it.copy(running = true) }
        return
      }
      outboxVisibility.enable()
      ditto.sync.start()
      nearbyNetworkingRunning = true
      diagnosticsState.update { it.copy(running = true) }
      beginReadinessWindow()
      visiblePeers
        .asSequence()
        .filterNot { it in incompatiblePeerKeys }
        .filter { localPeerKey < it }
        .forEach { peer ->
          ensureConnectJob(StreamKey(peer, LIVE_STREAM_NAME), DittoReliability.Unreliable)
          ensureConnectJob(StreamKey(peer, STATE_STREAM_NAME), DittoReliability.Reliable)
        }
    } else if (nearbyNetworkingRunning) {
      pauseStartedNetworking()
    } else {
      diagnosticsState.update { it.copy(running = false) }
    }
  }

  private fun pauseStartedNetworking() {
    // Close the admission gate before tearing resources down so presence/candidate callbacks
    // racing this method cannot recreate streams behind cleanup.
    nearbyNetworkingRunning = false
    synchronized(readinessLock) {
      ready = false
      initialReadinessResolved = false
      readinessDelayJob?.cancel()
      readinessDelayJob = null
      initialSyncTimeoutJob?.cancel()
      initialSyncTimeoutJob = null
      readinessGeneration.incrementAndGet()
      awaitingInitialSyncPeerKeys.clear()
      readinessWaivedPeerKeys.clear()
      synchronizedPeerKeys.clear()
    }
    diagnosticsState.update { current ->
      current.copy(
        editingReady = false,
        connectivityMessage = connectivityMessageAfterNewInitialSyncGate(
          current.connectivityMessage,
          newGateStarted = true,
        ),
      )
    }
    pauseNearbyNetworking()
    ditto.sync.stop()
    diagnosticsState.update { current ->
      current.copy(
        running = false,
        peers = current.peers.mapValues { (_, peer) ->
          peer.copy(liveConnected = false, stateConnected = false)
        },
      )
    }
  }

  private fun beginReadinessWindow() {
    synchronized(readinessLock) {
      ready = false
      initialReadinessResolved = false
      readinessDelayJob?.cancel()
      initialSyncTimeoutJob?.cancel()
      initialSyncTimeoutJob = null
      readinessGeneration.incrementAndGet()
      awaitingInitialSyncPeerKeys.clear()
      readinessWaivedPeerKeys.clear()
      synchronizedPeerKeys.clear()
    }
    diagnosticsState.update { current ->
      current.copy(
        editingReady = false,
        connectivityMessage =
          current.connectivityMessage?.takeUnless {
            it.startsWith("Initial nearby sync timed out")
          },
      )
    }
    val delayJob = scope.launch(start = CoroutineStart.LAZY) {
      delay(1_000)
      if (!foreground || !started) return@launch
      synchronized(readinessLock) {
        visiblePeers
          .asSequence()
          .filterNot { it in incompatiblePeerKeys }
          .forEach { peer ->
            tryAwaitInitialSync(
              peer,
              synchronizedPeerKeys,
              readinessWaivedPeerKeys,
              awaitingInitialSyncPeerKeys,
            )
          }
        ready = true
      }
      refreshEditingReadiness()
      streams.keys
        .filter { it.topic == STATE_STREAM_NAME }
        .forEach { sendHello(it.peerKey) }
    }
    synchronized(readinessLock) {
      readinessDelayJob = delayJob
    }
    delayJob.start()
  }

  private fun pauseNearbyNetworking() {
    connectJobs.values.forEach(Job::cancel)
    connectJobs.clear()
    recoveryJobs.values.forEach(Job::cancel)
    recoveryJobs.clear()
    snapshotJobs.values.forEach(Job::cancel)
    snapshotJobs.clear()
    reciprocalSnapshotJobs.values.forEach(Job::cancel)
    reciprocalSnapshotJobs.clear()
    snapshotRetryJobs.values.forEach(Job::cancel)
    snapshotRetryJobs.clear()
    snapshotRetryAttempts.clear()
    outboundSnapshotRetryJobs.values.forEach(Job::cancel)
    outboundSnapshotRetryJobs.clear()
    outboundSnapshotRetryAttempts.clear()
    snapshotRequested.clear()
    helloCoalesceJobs.values.forEach(Job::cancel)
    helloCoalesceJobs.clear()
    pendingHellos.clear()
    acceptOpenJobs.forEach(Job::cancel)
    acceptOpenJobs.clear()
    outboundSnapshots.values.forEach { it.timeoutJob?.cancel() }
    outboundSnapshots.clear()
    streams.values.forEach(DittoStream::close)
    streams.clear()
    outboxVisibility.disable {
      outboxJobs.values.forEach(Job::cancel)
      outboxJobs.clear()
      outboxes.values.forEach { it.close() }
      outboxes.clear()
      peerSendMutexes.clear()
    }
    operationDispatcher.close()
    synchronized(hydrationLock) {
      hydrations.values.forEach {
        it.timeoutJob?.cancel()
        it.finishJob?.cancel()
      }
      hydrations.clear()
    }
    chunkAssemblies.values.forEach { it.timeoutJob?.cancel() }
    chunkAssemblies.clear()
  }

  private fun configureTransports(context: Context) {
    val hasBle = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    val hasWifiAware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
      context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
    ditto.updateTransportConfig { config ->
      config.peerToPeer.bluetoothLe.enabled = hasBle
      config.peerToPeer.wifiAware.enabled = hasWifiAware
      config.peerToPeer.lan.enabled = true
      config.peerToPeer.lan.mdnsEnabled = true
      config.peerToPeer.lan.multicastEnabled = false
      config.listen.tcp.enabled = false
    }
  }

  private fun publishMetadata(profile: UserProfile) {
    ditto.presence.setPeerMetadata(
      mapOf(
        "application" to "ditto-whiteboard",
        "protocolVersion" to PROTOCOL_VERSION,
        "boardId" to BOARD_ID,
        "displayName" to profile.displayName,
        "colorArgb" to profile.colorArgb,
      ),
    )
  }

  private fun prepareNearbyNetworking(profile: UserProfile) {
    ditto.deviceName = profile.displayName
    publishMetadata(profile)
    installAcceptors()
    observePresence()
  }

  private fun installAcceptors() {
    if (acceptors.isNotEmpty()) return
    acceptors += bind(LIVE_STREAM_NAME, DittoReliability.Unreliable)
    acceptors += bind(STATE_STREAM_NAME, DittoReliability.Reliable)
  }

  private fun bind(topic: String, reliability: DittoReliability): DittoAcceptor =
    ditto.dataStreams.bindTopic(topic, reliability) { borrowedCandidate ->
      val peer = borrowedCandidate.peerKeyString()
      if (
        !nearbyLifecycleActive() ||
        peer !in visiblePeers ||
        peer >= localPeerKey ||
        peer in incompatiblePeerKeys
      ) return@bindTopic
      val candidate = borrowedCandidate.take()
      val job = scope.launch(ioDispatcher, start = CoroutineStart.LAZY) {
        try {
          val stream = candidate.use { owned ->
            if (!nearbyLifecycleActive() || peer !in visiblePeers) return@launch
            owned.open { inbound -> enqueueInbound(peer, topic, inbound.payload()) }
          }
          if (!nearbyLifecycleActive() || peer !in visiblePeers) {
            stream.close()
            return@launch
          }
          registerAndWait(StreamKey(peer, topic), stream)
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          recordError(peer, error)
        } finally {
          acceptOpenJobs -= currentCoroutineContext()[Job]
        }
      }
      acceptOpenJobs += job
      job.start()
    }

  private fun observePresence() {
    if (presenceJob?.isActive == true) return
    presenceJob = scope.launch {
      ditto.presence.observe()
        .onEach { graph ->
          if (presenceRecovering) {
            presenceRecovering = false
            diagnosticsState.update { current ->
              if (current.connectivityMessage?.startsWith("Nearby presence failed; retrying") == true) {
                current.copy(connectivityMessage = null)
              } else {
                current
              }
            }
          }
          val discoveredWhiteboardPeers = graph.remotePeers.filter { peer ->
            val metadata = peer.peerMetadata.toMap()
            metadata["application"] == "ditto-whiteboard" && metadata["boardId"] == BOARD_ID
          }
        // Every process selects the same lowest ten keys (including itself), avoiding asymmetric
        // connect attempts and bounding all per-peer queues/jobs even in a crowded radio space.
        val admittedKeys = admittedPeerKeys(
          localPeerKey,
          discoveredWhiteboardPeers.map { it.peerKey },
        )
        val whiteboardPeers = if (admittedKeys.isNotEmpty()) {
          discoveredWhiteboardPeers.filter { it.peerKey in admittedKeys }
        } else {
          emptyList()
        }
        val capacityLimited = discoveredWhiteboardPeers.size > whiteboardPeers.size
        val metadataIncompatible = whiteboardPeers.mapNotNull { peer ->
          val version = (peer.peerMetadata.toMap()["protocolVersion"] as? Number)?.toInt()
          if (version != null && version != PROTOCOL_VERSION) peer.peerKey to version else null
        }.toMap()
        synchronized(readinessLock) {
          incompatiblePeerKeys += metadataIncompatible.keys
        }
        val remote = whiteboardPeers
          .filter {
            it.peerKey !in metadataIncompatible &&
              it.peerKey !in incompatiblePeerKeys
          }
          .associateBy { it.peerKey }
        val presentPeerKeys = whiteboardPeers.map { it.peerKey }.toSet()
        val connections = admittedPresenceConnections(
          localPeerKey,
          presentPeerKeys,
          graph.remotePeers
          .flatMap { it.connections }
          .map { PresenceConnection(it.peer1, it.peer2, it.connectionType.name) }
        )
        outboxVisibility.update(presentPeerKeys) { removedPeers ->
          visiblePeers = presentPeerKeys
          removedPeers.forEach { peer ->
            outboxJobs.remove(peer)?.cancel()
            outboxes.remove(peer)?.close()
            peerSendMutexes.remove(peer)
          }
        }
        val removed = connectJobs.keys.filter { it.peerKey !in visiblePeers }
        removed.forEach { key -> connectJobs.remove(key)?.cancel() }
        streams.keys.filter { it.peerKey !in visiblePeers }.forEach { key -> streams.remove(key)?.close() }
        // Tear down outboxes and partial-transfer state for peers that left, so their drain loops
        // don't spin forever and their bounded channels can never wedge a producer.
        chunkAssemblies.keys.filter { it.first !in visiblePeers }.forEach { key ->
          chunkAssemblies.remove(key)?.timeoutJob?.cancel()
        }
        synchronized(hydrationLock) {
          hydrations.keys.filter { it !in visiblePeers }.forEach { peer ->
            hydrations.remove(peer)?.let {
              it.timeoutJob?.cancel()
              it.finishJob?.cancel()
            }
          }
        }
        snapshotJobs.keys.filter { it !in visiblePeers }.forEach { peer -> snapshotJobs.remove(peer)?.cancel() }
        reciprocalSnapshotJobs.keys.filter { it !in visiblePeers }.forEach { peer ->
          reciprocalSnapshotJobs.remove(peer)?.cancel()
        }
        snapshotRetryJobs.keys.filter { it !in visiblePeers }.forEach { peer -> snapshotRetryJobs.remove(peer)?.cancel() }
        snapshotRetryAttempts.keys.removeIf { it !in visiblePeers }
        outboundSnapshotRetryJobs.keys.filter { it !in visiblePeers }.forEach { peer ->
          outboundSnapshotRetryJobs.remove(peer)?.cancel()
        }
        outboundSnapshotRetryAttempts.keys.removeIf { it !in visiblePeers }
        snapshotRequested.removeIf { it !in visiblePeers }
        lastHelloNanos.keys.removeIf { it !in visiblePeers }
        pendingHellos.keys.removeIf { it !in visiblePeers }
        helloCoalesceJobs.keys.filter { it !in visiblePeers }.forEach { peer ->
          helloCoalesceJobs.remove(peer)?.cancel()
        }
        outboundSnapshots.keys.filter { it !in visiblePeers }.forEach { peer ->
          outboundSnapshots.remove(peer)?.timeoutJob?.cancel()
        }
        val outstandingInitialSync = synchronized(readinessLock) {
          incompatiblePeerKeys.removeIf { it !in visiblePeers }
          synchronizedPeerKeys.removeIf { it !in visiblePeers }
          awaitingInitialSyncPeerKeys.removeIf { it !in visiblePeers }
          readinessWaivedPeerKeys.removeIf { it !in visiblePeers }
          hasOutstandingInitialSync(
            visiblePeers,
            awaitingInitialSyncPeerKeys,
            readinessWaivedPeerKeys,
          )
        }
        recoveryJobs.keys.filter { it.peerKey !in visiblePeers }.forEach { key -> recoveryJobs.remove(key)?.cancel() }
        operationDispatcher.retainPeers(visiblePeers)
        // Drop traffic counters for departed peers too, otherwise the 1s rate worker re-inserts a
        // ghost PeerDiagnostics for every peer that ever sent/received traffic (via updatePeer),
        // resurrecting it in the connected-people count long after it left the mesh.
        (txCounters.keys + rxCounters.keys).filter { it !in visiblePeers }.toSet().forEach { peer ->
          txCounters.remove(peer)
          rxCounters.remove(peer)
        }

        remote.values.forEach { peer ->
          val newGateStarted = synchronized(readinessLock) {
            if (tryAwaitInitialSync(
              peer.peerKey,
              synchronizedPeerKeys,
              readinessWaivedPeerKeys,
              awaitingInitialSyncPeerKeys,
            )) {
              initialReadinessResolved = false
              true
            } else {
              false
            }
          }
          if (newGateStarted) {
            // Presence is continuous, not a one-shot startup list. A peer first discovered after
            // the original readiness window must gate editing until its digest is reconciled too.
            diagnosticsState.update { current ->
              current.copy(
                connectivityMessage = connectivityMessageAfterNewInitialSyncGate(
                  current.connectivityMessage,
                  newGateStarted = true,
                ),
              )
            }
          }
          val connectionNames = peer.connections.map { it.connectionType.name }.toSet()
          val metadata = peer.peerMetadata.toMap()
          val knownProfile = knownLog.profile(peer.peerKey)
          updatePeer(peer.peerKey) { current ->
            current.copy(
              displayName = knownProfile?.displayName ?: safeDisplayName(
                metadata["displayName"] as? String ?: peer.deviceName,
              ),
              colorArgb = knownProfile?.colorArgb ?: (metadata["colorArgb"] as? Number)?.toInt(),
              transports = connectionNames,
            )
          }
          if (localPeerKey < peer.peerKey) {
            ensureConnectJob(StreamKey(peer.peerKey, LIVE_STREAM_NAME), DittoReliability.Unreliable)
            ensureConnectJob(StreamKey(peer.peerKey, STATE_STREAM_NAME), DittoReliability.Reliable)
          }
        }
        refreshEditingReadiness()
        diagnosticsState.update { current ->
          current.copy(
            peers = current.peers.filterKeys { it in visiblePeers },
            incompatiblePeers =
              current.incompatiblePeers.filterKeys { it in visiblePeers } + metadataIncompatible,
            presenceConnections = connections,
            connectivityMessage = if (capacityLimited) {
              "Nearby board capacity is $MAX_CONNECTED_PEERS peers; additional peers are ignored."
            } else if (
              current.connectivityMessage?.startsWith("Nearby board capacity") == true
            ) {
              null
            } else if (
              !outstandingInitialSync &&
              current.connectivityMessage?.startsWith("Initial nearby sync timed out") == true
            ) {
              null
            } else {
              current.connectivityMessage
            },
          )
        }
        }
        .retryPresenceFailures(onFailure = { error ->
          presenceRecovering = true
          diagnosticsState.update { current ->
            current.copy(
              connectivityMessage = safeDiagnostic(
                "Nearby presence failed; retrying: ${error.message ?: error::class.java.simpleName}",
              ),
            )
          }
        })
        .collect()
    }
  }

  private fun ensureConnectJob(key: StreamKey, reliability: DittoReliability) {
    if (
      !nearbyLifecycleActive() ||
      key.peerKey in incompatiblePeerKeys ||
      streams.containsKey(key) ||
      connectJobs[key]?.isActive == true
    ) return
    connectJobs[key] = scope.launch(ioDispatcher) { connectLoop(key, reliability) }
  }

  private suspend fun connectLoop(key: StreamKey, reliability: DittoReliability) {
    var backoff = 500L
    while (
      scope.isActive &&
      nearbyLifecycleActive() &&
      key.peerKey in visiblePeers &&
      key.peerKey !in incompatiblePeerKeys &&
      !streams.containsKey(key)
    ) {
      try {
        val stream = ditto.dataStreams.connect(
          peer = key.peerKey,
          topic = key.topic,
          requestCompression = reliability == DittoReliability.Reliable,
          reliability = reliability,
          timeoutMs = 10_000,
        ) { candidate ->
          candidate.open { inbound -> enqueueInbound(key.peerKey, key.topic, inbound.payload()) }
        }
        backoff = 500
        registerAndWait(key, stream)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: DittoException.DataStreamsException) {
        // 5.1.0 can surface an in-flight cancellation as a DataStreamsException. Confirm the
        // coroutine is still active before treating this as a retryable connection failure,
        // otherwise a cancelled job would spuriously reconnect. (See SKILL.md.)
        currentCoroutineContext().ensureActive()
        recordError(key.peerKey, error)
      } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        recordError(key.peerKey, error)
      }
      if (
        nearbyLifecycleActive() &&
        key.peerKey in visiblePeers &&
        !streams.containsKey(key)
      ) {
        delay(backoff + Random.nextLong(0, min(250, backoff / 4) + 1))
        backoff = (backoff * 2).coerceAtMost(10_000)
      }
    }
  }

  private suspend fun registerAndWait(key: StreamKey, stream: DittoStream) {
    val registered = lifecycleMutex.withLock {
      if (!nearbyLifecycleActive() || key.peerKey !in visiblePeers) {
        false
      } else {
        streams.put(key, stream)?.takeIf { it !== stream }?.close()
        true
      }
    }
    if (!registered) {
      stream.close()
      return
    }
    if (key.topic == STATE_STREAM_NAME) {
      val newGateStarted = synchronized(readinessLock) {
        if (
          !initialReadinessResolved &&
          tryAwaitInitialSync(
          key.peerKey,
          synchronizedPeerKeys,
          readinessWaivedPeerKeys,
          awaitingInitialSyncPeerKeys,
          )
        ) {
          initialReadinessResolved = false
          true
        } else {
          false
        }
      }
      if (newGateStarted) refreshEditingReadiness()
      clearSnapshotRetry(key.peerKey)
      lastHelloNanos.remove(key.peerKey)
      outbox(key.peerKey)
      sendHello(key.peerKey)
    }
    refreshConnectionStatus(key.peerKey)
    try {
      stream.waitUntilClosed()
    } finally {
      streams.remove(key, stream)
      refreshConnectionStatus(key.peerKey)
    }
  }

  private fun enqueueInbound(peer: String, topic: String, payload: ByteArray) {
    rxCounters.computeIfAbsent(peer) { AtomicLong() }.incrementAndGet()
    val limit = if (topic == LIVE_STREAM_NAME) MAX_LIVE_FRAME_BYTES else MAX_RELIABLE_FRAME_BYTES
    if (payload.size > limit) {
      recoverStream(peer, topic, "Inbound $topic frame exceeds the protocol limit")
      return
    }
    val accepted = if (topic == LIVE_STREAM_NAME) {
      liveInbound.trySend(peer to payload).isSuccess
    } else {
      reliableInbound.trySend(peer to payload).isSuccess
    }
    if (!accepted && topic == STATE_STREAM_NAME) {
      recoverStream(peer, topic, "Reliable ingress saturated; reconnecting for snapshot repair")
    }
  }

  private fun startWorkers() {
    scope.launch {
      // Guard every frame: a single malformed reliable payload must never complete this consumer
      // (which would permanently halt reliable sync) or escape to the thread's default handler.
      for ((peer, payload) in reliableInbound) {
        try {
          handleReliable(peer, payload)
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          recordError(peer, error)
        }
      }
    }
    scope.launch {
      for ((peer, payload) in liveInbound) {
        if (hydrations.containsKey(peer)) continue
        when (val result = WhiteboardProtocol.decodeLive(payload, System.currentTimeMillis(), expectedPeerKey = peer)) {
          is ProtocolDecodeResult.Compatible -> mutableEvents.emit(TransportEvent.LivePreviewReceived(result.value))
          is ProtocolDecodeResult.Incompatible -> incompatible(peer, result.version)
          is ProtocolDecodeResult.Invalid -> recordError(peer, IllegalArgumentException(result.reason))
        }
      }
    }
    scope.launch {
      dispatchLiveFrames(
        frames = pendingLive,
        encode = { preview ->
          WhiteboardProtocol.encodeLive(
            preview,
            liveSequence.incrementAndGet(),
            liveSessionId,
          )
        },
        recipients = {
          streams.entries.filter { (key, _) -> key.topic == LIVE_STREAM_NAME }
        },
        maxSendSize = { (_, stream) -> stream.maxSendSize() },
        send = { (_, stream), bytes -> stream.sendAndForget(bytes.copyOf()) },
        onSent = { (key, _) ->
          txCounters.computeIfAbsent(key.peerKey) { AtomicLong() }.incrementAndGet()
        },
        onFailure = { recipient, error ->
          recipient?.key?.peerKey?.let { recordError(it, error) }
        },
        throttle = { delay(34) },
      )
    }
    scope.launch {
      while (isActive) {
        delay(1_000)
        (txCounters.keys + rxCounters.keys).forEach { peer ->
          // Never resurrect a departed peer: if a counter lingered past its presence removal (e.g.
          // an in-flight increment raced the prune in observePresence), drop it instead of
          // re-inserting a ghost diagnostics entry for a peer that is no longer visible.
          if (peer !in visiblePeers) {
            txCounters.remove(peer)
            rxCounters.remove(peer)
            return@forEach
          }
          val tx = txCounters[peer]?.getAndSet(0)?.toDouble() ?: 0.0
          val rx = rxCounters[peer]?.getAndSet(0)?.toDouble() ?: 0.0
          updatePeer(peer) { it.copy(transmitMessagesPerSecond = tx, receiveMessagesPerSecond = rx) }
        }
      }
    }
  }

  /**
   * Enqueues a reliable frame for [peer], applying backpressure via the bounded outbox. Never
   * resurrects an outbox for a peer that has already left the mesh, and tolerates a concurrently
   * closed channel, so producers can't wedge on a departed peer.
   */
  private suspend fun enqueueOutbox(peer: String, bytes: ByteArray) {
    val channel = outbox(peer) ?: return
    try {
      channel.send(bytes)
    } catch (closed: ClosedSendChannelException) {
      // Peer left between the visibility check and the send; drop the frame.
    }
  }

  private fun tryEnqueueOutbox(peer: String, bytes: ByteArray): Boolean =
    outbox(peer)?.trySend(bytes)?.isSuccess == true

  /**
   * Closes a bad or saturated stream at most once per peer/topic. The active connector's bounded
   * reconnect loop opens a fresh stream, whose Hello/digest exchange repairs anything not queued.
   */
  private fun recoverStream(peer: String, topic: String, reason: String) {
    val key = StreamKey(peer, topic)
    recoveryJobs.compute(key) { _, existing ->
      if (existing?.isActive == true) existing else scope.launch {
        try {
          recordError(peer, reason)
          streams.remove(key)?.close()
        } finally {
          recoveryJobs.remove(key, currentCoroutineContext()[Job])
        }
      }
    }
  }

  private fun outbox(peer: String): Channel<ByteArray>? = outboxVisibility.ifVisible(peer) {
    outboxes.computeIfAbsent(peer) {
      // Bounded so a stalled peer applies backpressure to its producers instead of growing an
      // unbounded queue. The SDK itself offers no backpressure, so this is the app's safety valve.
      Channel<ByteArray>(RELIABLE_OUTBOX_CAPACITY).also { channel ->
        outboxJobs[peer] = scope.launch(ioDispatcher) {
          for (payload in channel) {
          var sent = false
          var attempts = 0
          var streamlessWaits = 0
          while (isActive && !sent) {
            val stream = streams[StreamKey(peer, STATE_STREAM_NAME)]
            if (stream == null) {
              // No stream yet. Wait for a reconnect, but stop waiting once the peer has left so
              // the drain can never block the channel (and its producers) indefinitely. Also bound
              // the wait for a peer that stays visible but never gets a stream: otherwise the drain
              // sits here forever, the channel wedges at capacity, and the reliable fan-out to every
              // other peer stalls behind it. Drop the frame after the bound; it re-heals via snapshot.
              if (peer !in visiblePeers) break
              if (++streamlessWaits > MAX_STREAMLESS_WAITS) break
              delay(100)
            } else {
              streamlessWaits = 0
              try {
                peerSendMutexes.computeIfAbsent(peer) { Mutex() }.withLock {
                  sendFramed(peer, stream, payload)
                }
                sent = true
              } catch (cancelled: CancellationException) {
                throw cancelled
              } catch (error: Exception) {
                recordError(peer, error)
                streams.remove(StreamKey(peer, STATE_STREAM_NAME), stream)
                stream.close()
                // Give up on this frame after repeated failures so it can't spin forever and hold
                // the channel full. Operations are idempotent and the snapshot path re-heals state.
                if (++attempts >= MAX_SEND_ATTEMPTS) break
                delay(250)
              }
            }
          }
          }
        }
      }
    }
  }

  private suspend fun sendFramed(peer: String, stream: DittoStream, payload: ByteArray) {
    val max = stream.maxSendSize().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    if (payload.size <= max) {
      sendChecked(peer, stream, payload)
      return
    }
    // Content-addressed transfer id so a re-send of the same payload (after a stream failure) reuses
    // the same id: the receiver re-fills the same assembly instead of leaking an orphaned partial one.
    val transfer = SnapshotTransfer.create(payload, (max - 768).coerceAtLeast(256), id = WhiteboardProtocol.sha256(payload).toHex())
    transfer.chunks.forEachIndexed { index, chunk ->
      val envelope = baseEnvelope().setReliableChunk(
        ReliableChunk.newBuilder()
          .setTransferId(transfer.id)
          .setChunkCount(transfer.chunks.size)
          .setByteCount(payload.size.toLong())
          .setSha256(ByteString.copyFrom(transfer.digest))
          .setIndex(index)
          .setData(ByteString.copyFrom(chunk)),
      ).build().toByteArray()
      require(envelope.size <= max) { "Ditto maxSendSize is too small for a reliable chunk envelope" }
      sendChecked(peer, stream, envelope)
    }
  }

  /**
   * Sends one reliable frame and inspects the terminal [DittoSendStatus]. A [DittoSendStatus.Failed]
   * is thrown so the outbox loop tears down the stream and re-sends — dropping a "reliable"
   * operation silently would let boards diverge. Operations are idempotent, so a resend is safe.
   * A [DittoSendStatus.Cancelled] is only benign when our own coroutine was cancelled (shutdown):
   * [ensureActive] throws in that case and unwinds cleanly. If the coroutine is still active, a
   * Cancelled came from the SDK (e.g. the stream closing mid-send), so it is treated exactly like
   * [DittoSendStatus.Failed] — thrown to tear down the stream and resend, rather than silently
   * dropping a "reliable" frame and letting boards diverge. Operations are idempotent, so a resend
   * is safe.
   */
  private suspend fun sendChecked(peer: String, stream: DittoStream, bytes: ByteArray) {
    when (stream.send(bytes)) {
      DittoSendStatus.Sent -> txCounters.computeIfAbsent(peer) { AtomicLong() }.incrementAndGet()
      DittoSendStatus.Failed -> throw IllegalStateException("Reliable send to ${peer.takeLast(6)} failed")
      DittoSendStatus.Cancelled -> {
        // Throws if the coroutine was cancelled (shutdown); otherwise falls through to the throw.
        currentCoroutineContext().ensureActive()
        throw IllegalStateException("Reliable send to ${peer.takeLast(6)} was cancelled by the SDK")
      }
      DittoSendStatus.Unknown ->
        throw IllegalStateException("Reliable send to ${peer.takeLast(6)} ended with unknown status")
      DittoSendStatus.Pending ->
        throw IllegalStateException("Reliable send to ${peer.takeLast(6)} returned a non-terminal status")
    }
  }

  private suspend fun handleReliable(
    peer: String,
    payload: ByteArray,
    allowChunkEnvelope: Boolean = true,
  ) {
    when (val result = WhiteboardProtocol.decodeEnvelope(payload, expectedPeerKey = peer)) {
      is ProtocolDecodeResult.Incompatible -> incompatible(peer, result.version)
      is ProtocolDecodeResult.Invalid -> recordError(peer, IllegalArgumentException(result.reason))
      is ProtocolDecodeResult.Compatible -> {
        val envelope = result.value
        when (envelope.payloadCase) {
          Envelope.PayloadCase.HELLO -> handleHello(peer, envelope)
          Envelope.PayloadCase.OPERATION ->
            // Peer-supplied JSON; a malformed operation must be rejected, not thrown (mirrors decodeLive).
            when (val operation = runCatchingException {
              WhiteboardProtocol.decodeOperation(envelope, expectedPeerKey = peer)
            }.getOrNull()) {
              null -> recordError(peer, IllegalArgumentException("Malformed reliable operation"))
              else -> {
                if (!operationDispatcher.tryDispatch(peer, operation)) {
                  recoverStream(
                    peer,
                    STATE_STREAM_NAME,
                    "Reliable operation application queue saturated",
                  )
                }
              }
            }
          Envelope.PayloadCase.SNAPSHOT_BEGIN -> beginHydration(peer, envelope)
          Envelope.PayloadCase.SNAPSHOT_CHUNK -> {
            if (
              envelope.snapshotChunk.transferId.isBlank() ||
              envelope.snapshotChunk.transferId.length > MAX_TRANSFER_ID_LENGTH
            ) {
              recordError(peer, "Invalid snapshot chunk transfer id")
            } else {
              handleSnapshotChunk(peer, envelope.snapshotChunk)
            }
          }
          Envelope.PayloadCase.SNAPSHOT_END -> {
            val transferId = envelope.snapshotEnd.transferId
            if (transferId.isBlank() || transferId.length > MAX_TRANSFER_ID_LENGTH) {
              recordError(peer, "Invalid snapshot end transfer id")
            } else {
              launchHydrationFinish(peer, transferId)
            }
          }
          Envelope.PayloadCase.SNAPSHOT_ACK -> {
            if (envelope.snapshotAck.error.length > MAX_DIAGNOSTIC_LENGTH) {
              recordError(peer, "Invalid snapshot acknowledgement error")
            } else {
              handleSnapshotAck(peer, envelope.snapshotAck)
            }
          }
          Envelope.PayloadCase.RELIABLE_CHUNK -> {
            if (allowChunkEnvelope) {
              handleChunk(peer, envelope.reliableChunk)
            } else {
              recoverStream(peer, STATE_STREAM_NAME, "Nested reliable chunks are not allowed")
            }
          }
          Envelope.PayloadCase.PAYLOAD_NOT_SET, null -> Unit
        }
      }
    }
  }

  private fun handleHello(peer: String, envelope: Envelope) {
    pendingHellos[peer] = envelope
    ensureHelloCoalescer(peer)
  }

  private fun ensureHelloCoalescer(peer: String) {
    helloCoalesceJobs.compute(peer) { key, existing ->
      if (existing?.isActive == true) {
        existing
      } else {
        scope.launch {
          try {
            while (isActive) {
              val previous = lastHelloNanos[key]
              if (previous != null) {
                val remainingNanos =
                  HELLO_MIN_INTERVAL_NANOS - (System.nanoTime() - previous)
                if (remainingNanos > 0) {
                  // Ceiling division prevents an early sub-millisecond wake from orphaning the
                  // latest pending Hello without another scheduled coalescer.
                  delay((remainingNanos + 999_999L) / 1_000_000L)
                }
              }
              val latest = pendingHellos.remove(key) ?: break
              lastHelloNanos[key] = System.nanoTime()
              processHello(key, latest)
            }
          } finally {
            helloCoalesceJobs.remove(key, currentCoroutineContext()[Job])
            // A Hello can arrive between the final empty check and map removal.
            if (pendingHellos.containsKey(key)) ensureHelloCoalescer(key)
          }
        }
      }
    }
  }

  private suspend fun processHello(peer: String, envelope: Envelope) {
    runCatchingException {
      WhiteboardProtocol.decodeProfile(envelope.hello.profileJson.toByteArray(), expectedPeerKey = peer)
    }.getOrNull()?.let { remoteProfile ->
      updatePeer(peer) { it.copy(displayName = remoteProfile.displayName, colorArgb = remoteProfile.colorArgb) }
    }
    val material = stateMaterial()
    val remoteDigest = envelope.hello.stateDigest.toByteArray()
    val digestMatches = remoteDigest.contentEquals(material.digest)
    traceReadiness(
      "hello peer=${peer.takeLast(6)} localOps=${material.state.operations.size} " +
        "local=${material.digest.tracePrefix()} remote=${remoteDigest.tracePrefix()} match=$digestMatches",
    )
    if (digestMatches) {
      markInitialSyncComplete(peer)
    } else {
      val newGateStarted = synchronized(readinessLock) {
        if (
          tryAwaitInitialSync(
            peer,
            synchronizedPeerKeys,
            readinessWaivedPeerKeys,
            awaitingInitialSyncPeerKeys,
          )
        ) {
          initialReadinessResolved = false
          true
        } else {
          false
        }
      }
      if (newGateStarted) refreshEditingReadiness()
    }
    val snapshotsReady = synchronized(readinessLock) { ready }
    if (
      snapshotsReady &&
      shouldOfferSnapshot(
        material.state,
        remoteDigest,
        material.digest,
      )
    ) {
      // Launch the multi-chunk snapshot off the shared reliable worker so a slow/backed-up peer
      // can't head-of-line block inbound processing for every other peer. Serialize per peer: if a
      // transfer to this peer is still in flight, skip — enqueuing a second one concurrently would
      // interleave its frames with the first on the peer's ordered outbox. A skipped peer still
      // converges via the in-flight snapshot plus operations relayed through sendReliable, and any
      // receiver-side snapshot timeout re-hellos once the first transfer has drained.
      launchSnapshot(peer)
    }
  }

  private fun launchSnapshot(peer: String) {
    if (outboundSnapshots.containsKey(peer)) return
    snapshotJobs.compute(peer) { key, existing ->
      if (existing?.isActive == true) {
        snapshotRequested += key
        existing
      } else {
        snapshotRequested -= key
        scope.launch {
          try {
            sendSnapshot(key)
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (error: Exception) {
            outboundSnapshots.remove(key)?.timeoutJob?.cancel()
            recordError(key, error)
            recoverStream(key, STATE_STREAM_NAME, "Snapshot send failed; reconnecting")
            scheduleOutboundSnapshotRetry(key, "Snapshot send failed")
          } finally {
            snapshotJobs.remove(key, currentCoroutineContext()[Job])
            if (
              snapshotRequested.remove(key) &&
              key in visiblePeers &&
              !outboundSnapshots.containsKey(key)
            ) {
              launchSnapshot(key)
            }
          }
        }
      }
    }
  }

  private suspend fun handleOperation(peer: String, operation: BoardOperation) {
    if (operation.stamp.lamport > MAX_SESSION_LAMPORT) {
      recoverStream(peer, STATE_STREAM_NAME, "Operation exceeds the board logical-time limit")
      return
    }
    var rejectedHydration: SnapshotHydration? = null
    val buffered = synchronized(hydrationLock) {
      val hydration = hydrations[peer] ?: return@synchronized false
      val existing = hydration.bufferedOperations[operation.id]
      val winner = existing?.let { canonicalOperation(it, operation) } ?: operation
      val byteDelta = WhiteboardProtocol.operationJsonByteCount(winner) -
        (existing?.let { WhiteboardProtocol.operationJsonByteCount(it) } ?: 0)
      if (
        (existing == null && hydration.bufferedOperations.size >= MAX_BUFFERED_HYDRATION_OPERATIONS) ||
        hydration.bufferedOperationBytes + byteDelta > MAX_BUFFERED_HYDRATION_BYTES
      ) {
        if (hydrations.remove(peer, hydration)) {
          hydration.timeoutJob?.cancel()
          hydration.finishJob?.cancel()
          rejectedHydration = hydration
        }
        false
      } else {
        hydration.bufferedOperations[operation.id] = winner
        hydration.bufferedOperationBytes += byteDelta
        true
      }
    }
    if (buffered) return
    rejectedHydration?.let {
      rejectRemovedHydration(peer, it, "Too many operations arrived during snapshot hydration")
    }
    deliverOperation(peer, operation)
  }

  private suspend fun deliverOperation(peer: String, operation: BoardOperation) {
    val prepared = CompletableDeferred<Boolean>()
    val committed = CompletableDeferred<Boolean>()
    val applied = CompletableDeferred<Boolean>()
    try {
      mutableEvents.emit(
        TransportEvent.ReliableOperationReceived(
          operation = operation,
          prepared = prepared,
          committed = committed,
          applied = applied,
        ),
      )
      if (withTimeoutOrNull(TRANSFER_INACTIVITY_TIMEOUT_MILLIS) { prepared.await() } != true) {
        committed.complete(false)
        recoverStream(peer, STATE_STREAM_NAME, "Reliable operation was not applied by the board session")
        return
      }
      val result = acceptKnownOperation(operation)
      if (result is KnownOperationResult.Rejected) {
        committed.complete(false)
        recoverStream(peer, STATE_STREAM_NAME, result.reason)
        return
      }
      committed.complete(true)
      if (withTimeoutOrNull(TRANSFER_INACTIVITY_TIMEOUT_MILLIS) { applied.await() } != true) {
        recoverStream(peer, STATE_STREAM_NAME, "Reliable operation apply acknowledgement timed out")
        return
      }
      if (operation is BoardOperation.ProfileUpdate) {
        updatePeer(peer) {
          it.copy(displayName = operation.profile.displayName, colorArgb = operation.profile.colorArgb)
        }
      }
    } finally {
      // Presence/background cancellation must never strand BoardSession awaiting one phase.
      prepared.complete(false)
      committed.complete(false)
      applied.complete(false)
    }
  }

  private suspend fun beginHydration(peer: String, envelope: Envelope) {
    val begin = envelope.snapshotBegin
    invalidTransferHeader(
      begin.transferId,
      begin.chunkCount,
      begin.byteCount,
      begin.sha256.toByteArray(),
    )?.let { reason ->
      recordError(peer, reason)
      sendSnapshotAck(peer, begin.transferId.take(MAX_TRANSFER_ID_LENGTH), false, reason)
      scheduleSnapshotRetry(peer, reason, resendOutbound = false)
      return
    }
    val hydration = SnapshotHydration(
      transferId = begin.transferId,
      assembler = SnapshotAssembler(begin.transferId, begin.chunkCount, begin.byteCount, begin.sha256.toByteArray()),
      chunkCount = begin.chunkCount,
    )
    var busy = false
    val previous = synchronized(hydrationLock) {
      if (hydrations.keys.any { it != peer }) {
        busy = true
        null
      } else {
        hydrations.put(peer, hydration).also { old -> old?.timeoutJob?.cancel() }
      }
    }
    if (busy) {
      sendSnapshotAck(peer, begin.transferId, false, "Another snapshot is already being received")
      scheduleSnapshotRetry(
        peer,
        "Another snapshot is already being received",
        resendOutbound = false,
      )
      return
    }
    previous?.let {
      rejectRemovedHydration(peer, it, "Snapshot was superseded by a newer transfer", retry = false)
    }
    resetHydrationTimeout(peer, hydration)
    updatePeer(peer) { it.copy(snapshotStatus = SnapshotStatus.Receiving, snapshotProgress = 0f) }
  }

  private suspend fun handleSnapshotChunk(peer: String, chunk: SnapshotChunk) {
    var rejected: Pair<SnapshotHydration, String>? = null
    var progress: Float? = null
    val hydration = synchronized(hydrationLock) {
      val current = hydrations[peer] ?: return@synchronized null
      if (chunk.transferId != current.transferId) return@synchronized null
      if (current.finishing) return@synchronized null
      when (val result = current.assembler.add(chunk.index, chunk.data.toByteArray())) {
        is SnapshotAssemblyResult.Rejected -> {
          if (hydrations.remove(peer, current)) {
            current.timeoutJob?.cancel()
            rejected = current to result.reason
          }
        }
        else -> {
          current.receivedChunks += chunk.index
          progress = current.receivedChunks.size.toFloat() / current.chunkCount
        }
      }
      current
    } ?: return
    rejected?.let {
      rejectRemovedHydration(peer, it.first, it.second)
      return
    }
    resetHydrationTimeout(peer, hydration)
    progress?.let { value ->
      updatePeer(peer) { it.copy(snapshotProgress = value) }
    }
  }

  /**
   * Snapshot parsing and the session's prepare/commit/apply handshake must not occupy the one
   * reliable-ingress worker. Keep this hydration installed while it finalizes so operations that
   * follow SnapshotEnd are still bounded and replayed after the snapshot is committed.
   */
  private fun launchHydrationFinish(peer: String, transferId: String) {
    val hydration = synchronized(hydrationLock) {
      val current = hydrations[peer]?.takeIf { it.transferId == transferId } ?: return@synchronized null
      if (current.finishing || current.finishJob != null) return@synchronized null
      current.finishing = true
      current.timeoutJob?.cancel()
      current
    } ?: return
    val job = scope.launch(ioDispatcher, start = CoroutineStart.LAZY) {
      try {
        finishHydration(peer, hydration)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        recordError(peer, error)
        rejectHydration(peer, hydration, error.message ?: "Snapshot finalization failed")
      }
    }
    synchronized(hydrationLock) {
      if (hydrations[peer] !== hydration || hydration.finishJob != null) {
        job.cancel()
        return
      }
      hydration.finishJob = job
    }
    job.start()
  }

  private suspend fun finishHydration(peer: String, hydration: SnapshotHydration) {
    val transferId = hydration.transferId
    when (val result = hydration.assembler.finish(transferId)) {
      is SnapshotAssemblyResult.Complete -> {
        val snapshotOperations = runCatchingException {
          WhiteboardProtocol.snapshotOperations(result.bytes)
        }.getOrElse { error ->
          rejectHydration(peer, hydration, error.message ?: "Invalid snapshot")
          return
        }
        if (snapshotOperations.any { it.stamp.lamport > MAX_SESSION_LAMPORT }) {
          rejectHydration(
            peer,
            hydration,
            "Snapshot exceeds the board logical-time limit",
          )
          return
        }
        val initiallyBuffered = synchronized(hydrationLock) {
          if (hydrations[peer] !== hydration) return
          hydration.bufferedOperations.toMap()
        }
        val mergedOperations = snapshotOperations + initiallyBuffered.values
        val remoteMaterial = LinkedHashMap<OperationId, BoardOperation>()
        mergedOperations.forEach { operation ->
          remoteMaterial[operation.id] = remoteMaterial[operation.id]
            ?.let { existing -> canonicalOperation(existing, operation) }
            ?: operation
        }
        val remoteMaterialDigest =
          WhiteboardProtocol.operationSetDigest(remoteMaterial.values)
        val prepared = CompletableDeferred<Boolean>()
        val committed = CompletableDeferred<Boolean>()
        val applied = CompletableDeferred<Boolean>()
        try {
          mutableEvents.emit(
            TransportEvent.SnapshotMerged(
              operations = mergedOperations,
              prepared = prepared,
              committed = committed,
              applied = applied,
            ),
          )
          if (withTimeoutOrNull(TRANSFER_INACTIVITY_TIMEOUT_MILLIS) { prepared.await() } != true) {
            committed.complete(false)
            rejectHydration(peer, hydration, "Snapshot could not be applied by the board session")
            return
          }
          val mergeError = mergeKnownOperations(mergedOperations)
          if (mergeError != null) {
            committed.complete(false)
            rejectHydration(peer, hydration, mergeError)
            return
          }
          committed.complete(true)
          if (withTimeoutOrNull(TRANSFER_INACTIVITY_TIMEOUT_MILLIS) { applied.await() } != true) {
            rejectHydration(peer, hydration, "Snapshot apply acknowledgement timed out")
            return
          }
          val trailingOperations = synchronized(hydrationLock) {
            if (!hydrations.remove(peer, hydration)) return
            hydration.timeoutJob?.cancel()
            hydration.bufferedOperations
              .filter { (id, operation) -> initiallyBuffered[id] != operation }
              .values
              .toList()
          }
          val mergedMaterial = stateMaterial()
          val reciprocalSnapshotNeeded = shouldSendReciprocalSnapshot(
            remoteMaterialDigest,
            mergedMaterial.digest,
          )
          updatePeer(peer) { it.copy(snapshotStatus = SnapshotStatus.Merged, snapshotProgress = 1f) }
          traceReadiness(
            "snapshot merged peer=${peer.takeLast(6)} received=${snapshotOperations.size} " +
              "known=${stateMaterial().state.operations.size} digest=${stateMaterial().digest.tracePrefix()}",
          )
          clearInboundSnapshotRetry(peer)
          sendSnapshotAck(peer, transferId, true, "")
          markInitialSyncComplete(peer)
          if (reciprocalSnapshotNeeded) requestReciprocalSnapshot(peer)
          trailingOperations.forEach { operation -> deliverOperation(peer, operation) }
          // Advertise the merged digest immediately. This coalesces a full-mesh late join: other
          // peers that were rejected while the one global hydration slot was occupied see equality
          // before retrying and do not send duplicate 16 MiB snapshots.
          streams.keys
            .asSequence()
            .filter { it.topic == STATE_STREAM_NAME }
            .map(StreamKey::peerKey)
            .distinct()
            .forEach { connectedPeer -> sendHello(connectedPeer) }
        } finally {
          prepared.complete(false)
          committed.complete(false)
          applied.complete(false)
        }
      }
      is SnapshotAssemblyResult.Rejected -> rejectHydration(peer, hydration, result.reason)
      SnapshotAssemblyResult.Pending ->
        rejectHydration(peer, hydration, "Snapshot ended before all chunks arrived")
    }
  }

  private suspend fun rejectHydration(
    peer: String,
    hydration: SnapshotHydration,
    reason: String,
    retry: Boolean = true,
  ) {
    val currentJob = currentCoroutineContext()[Job]
    val removed = synchronized(hydrationLock) {
      if (!hydrations.remove(peer, hydration)) return@synchronized false
      hydration.timeoutJob?.cancel()
      val finishJob = hydration.finishJob
      if (finishJob != currentJob) finishJob?.cancel()
      true
    }
    if (!removed) return
    rejectRemovedHydration(peer, hydration, reason, retry)
  }

  private suspend fun rejectRemovedHydration(
    peer: String,
    hydration: SnapshotHydration,
    reason: String,
    retry: Boolean = true,
  ) {
    val currentJob = currentCoroutineContext()[Job]
    if (hydration.finishJob != currentJob) hydration.finishJob?.cancel()
    // Operations received after SnapshotBegin are independently valid reliable messages. Re-apply
    // them after rejecting the snapshot instead of silently discarding them with the transfer.
    hydration.bufferedOperations.values.forEach { deliverOperation(peer, it) }
    updatePeer(peer) { it.copy(snapshotStatus = SnapshotStatus.Rejected, lastError = reason) }
    sendSnapshotAck(peer, hydration.transferId, false, reason.take(256))
    if (retry) scheduleSnapshotRetry(peer, reason, resendOutbound = false)
  }

  /**
   * Treats the timeout as inactivity, resetting on every valid chunk, while retaining a hard
   * five-minute lifetime so a peer cannot pin a 16 MiB assembly forever by trickling duplicates.
   */
  private fun resetHydrationTimeout(peer: String, hydration: SnapshotHydration) {
    synchronized(hydrationLock) {
      if (hydrations[peer] !== hydration) return
      hydration.timeoutJob?.cancel()
      val ageMillis = (System.nanoTime() - hydration.startedAtNanos) / 1_000_000
      val remainingLifetime = MAX_SNAPSHOT_TRANSFER_LIFETIME_MILLIS - ageMillis
      if (remainingLifetime <= 0) {
        hydration.timeoutJob = scope.launch {
          rejectHydration(peer, hydration, "Snapshot exceeded its maximum transfer lifetime")
        }
        return
      }
      hydration.timeoutJob = scope.launch {
        delay(minOf(TRANSFER_INACTIVITY_TIMEOUT_MILLIS, remainingLifetime))
        rejectHydration(peer, hydration, "Snapshot transfer timed out")
      }
    }
  }

  private fun invalidTransferHeader(
    transferId: String,
    chunkCount: Int,
    byteCount: Long,
    digest: ByteArray,
    maximumByteCount: Long = MAX_TRANSFER_BYTES,
  ): String? = when {
    transferId.isBlank() || transferId.length > MAX_TRANSFER_ID_LENGTH -> "Invalid transfer id"
    chunkCount !in 1..MAX_TRANSFER_CHUNKS -> "Invalid transfer chunk count"
    byteCount !in 1..maximumByteCount -> "Invalid transfer byte count"
    digest.size != SHA_256_BYTE_COUNT -> "Invalid transfer digest"
    else -> null
  }

  private suspend fun handleChunk(peer: String, chunk: ReliableChunk) {
    val digest = chunk.sha256.toByteArray()
    invalidTransferHeader(
      chunk.transferId,
      chunk.chunkCount,
      chunk.byteCount,
      digest,
      maximumByteCount = MAX_RELIABLE_FRAME_BYTES.toLong(),
    )?.let { reason ->
      recoverStream(peer, STATE_STREAM_NAME, reason)
      return
    }
    val key = peer to chunk.transferId
    val existingAssembly = chunkAssemblies[key]
    val assembly = if (existingAssembly == null) {
      if (chunkAssemblies.keys.count { it.first == peer } >= MAX_CHUNK_ASSEMBLIES_PER_PEER) {
        recoverStream(peer, STATE_STREAM_NAME, "Too many incomplete reliable transfers")
        return
      }
      val created = ChunkAssembly(
        SnapshotAssembler(chunk.transferId, chunk.chunkCount, chunk.byteCount, chunk.sha256.toByteArray()),
        expectedCount = chunk.chunkCount,
        expectedByteCount = chunk.byteCount,
        expectedDigest = digest,
      )
      chunkAssemblies[key] = created
      resetReliableTransferTimeout(peer, key, created)
      created
    } else {
      if (
        existingAssembly.expectedCount != chunk.chunkCount ||
        existingAssembly.expectedByteCount != chunk.byteCount ||
        !existingAssembly.expectedDigest.contentEquals(digest)
      ) {
        chunkAssemblies.remove(key, existingAssembly)
        existingAssembly.timeoutJob?.cancel()
        recoverStream(peer, STATE_STREAM_NAME, "Reliable transfer metadata changed mid-stream")
        return
      }
      existingAssembly
    }
    when (val result = assembly.assembler.add(chunk.index, chunk.data.toByteArray())) {
      is SnapshotAssemblyResult.Rejected -> {
        chunkAssemblies.remove(key, assembly)
        assembly.timeoutJob?.cancel()
        recoverStream(peer, STATE_STREAM_NAME, result.reason)
        return
      }
      else -> Unit
    }
    assembly.received += chunk.index
    resetReliableTransferTimeout(peer, key, assembly)
    if (assembly.received.size == assembly.expectedCount) {
      chunkAssemblies.remove(key, assembly)
      assembly.timeoutJob?.cancel()
      val complete = assembly.assembler.finish(chunk.transferId)
      if (complete is SnapshotAssemblyResult.Complete) {
        handleReliable(peer, complete.bytes, allowChunkEnvelope = false)
      }
      else if (complete is SnapshotAssemblyResult.Rejected) recoverStream(peer, STATE_STREAM_NAME, complete.reason)
    }
  }

  private fun resetReliableTransferTimeout(
    peer: String,
    key: Pair<String, String>,
    assembly: ChunkAssembly,
  ) {
    if (chunkAssemblies[key] !== assembly) return
    assembly.timeoutJob?.cancel()
    val ageMillis = (System.nanoTime() - assembly.startedAtNanos) / 1_000_000
    val remainingLifetime = MAX_RELIABLE_TRANSFER_LIFETIME_MILLIS - ageMillis
    assembly.timeoutJob = scope.launch {
      delay(minOf(RELIABLE_TRANSFER_TIMEOUT_MILLIS, remainingLifetime.coerceAtLeast(0)))
      if (chunkAssemblies.remove(key, assembly)) {
        recoverStream(peer, STATE_STREAM_NAME, "Reliable transfer timed out; reconciling")
      }
    }
  }

  private fun handleSnapshotAck(peer: String, ack: SnapshotAck) {
    if (ack.transferId.isBlank() || ack.transferId.length > MAX_TRANSFER_ID_LENGTH) {
      recordError(peer, "Invalid snapshot acknowledgement")
      return
    }
    val outbound = outboundSnapshots[peer]
    if (outbound?.transferId != ack.transferId) {
      recordError(peer, "Snapshot acknowledgement does not match the active transfer")
      return
    }
    if (ack.accepted) {
      traceReadiness("snapshot acknowledged peer=${peer.takeLast(6)} id=${ack.transferId.take(8)}")
      outboundSnapshots.remove(peer, outbound)
      outbound.timeoutJob?.cancel()
      clearOutboundSnapshotRetry(peer)
      updatePeer(peer) {
        it.copy(snapshotStatus = SnapshotStatus.Acknowledged, snapshotProgress = 1f)
      }
    } else {
      val reason = safeDiagnostic(ack.error.ifBlank { "Remote peer rejected the snapshot" })
      outboundSnapshots.remove(peer, outbound)
      outbound.timeoutJob?.cancel()
      // Stop enqueueing chunks after an immediate BEGIN rejection. The outbound retry has its own
      // direction-specific state and will start only after this producer has unwound.
      snapshotJobs.remove(peer)?.cancel()
      updatePeer(peer) { it.copy(snapshotStatus = SnapshotStatus.Rejected, lastError = reason) }
      scheduleOutboundSnapshotRetry(peer, reason)
    }
  }

  private fun scheduleSnapshotRetry(
    peer: String,
    reason: String,
    resendOutbound: Boolean,
  ) {
    if (peer !in visiblePeers) return
    val attempt = snapshotRetryAttempts.computeIfAbsent(peer) { AtomicInteger() }.incrementAndGet()
    if (attempt > MAX_SNAPSHOT_RETRIES) {
      recordError(peer, "Snapshot retry limit reached: $reason")
      snapshotRetryAttempts.remove(peer)
      outboundSnapshots.remove(peer)?.timeoutJob?.cancel()
      recoverStream(peer, STATE_STREAM_NAME, "Snapshot retry limit reached; reconnecting")
      return
    }
    snapshotRetryJobs.compute(peer) { _, existing ->
      if (existing?.isActive == true) existing else scope.launch {
        try {
          delay(250L shl (attempt - 1))
          if (resendOutbound) launchSnapshot(peer) else sendHello(peer)
        } finally {
          snapshotRetryJobs.remove(peer, currentCoroutineContext()[Job])
        }
      }
    }
  }

  private fun clearSnapshotRetry(peer: String) {
    clearInboundSnapshotRetry(peer)
    clearOutboundSnapshotRetry(peer)
  }

  private fun clearInboundSnapshotRetry(peer: String) {
    snapshotRetryJobs.remove(peer)?.cancel()
    snapshotRetryAttempts.remove(peer)
  }

  private fun clearOutboundSnapshotRetry(peer: String) {
    outboundSnapshotRetryJobs.remove(peer)?.cancel()
    outboundSnapshotRetryAttempts.remove(peer)
  }

  private fun scheduleOutboundSnapshotRetry(peer: String, reason: String) {
    if (peer !in visiblePeers) return
    val attempt =
      outboundSnapshotRetryAttempts.computeIfAbsent(peer) { AtomicInteger() }.incrementAndGet()
    if (attempt > MAX_SNAPSHOT_RETRIES) {
      recordError(peer, "Snapshot resend limit reached: $reason")
      outboundSnapshotRetryAttempts.remove(peer)
      recoverStream(peer, STATE_STREAM_NAME, "Snapshot resend limit reached; reconnecting")
      return
    }
    outboundSnapshotRetryJobs.compute(peer) { key, existing ->
      if (existing?.isActive == true) existing else scope.launch {
        try {
          delay(250L shl (attempt - 1))
          launchSnapshot(key)
        } finally {
          outboundSnapshotRetryJobs.remove(key, currentCoroutineContext()[Job])
        }
      }
    }
  }

  /**
   * A received merge-only snapshot can be a strict subset of local history. Send the merged union
   * back once any older outbound transfer has been acknowledged, so convergence does not depend on
   * a one-shot Hello being delivered in both directions.
   */
  private fun requestReciprocalSnapshot(peer: String) {
    if (peer !in visiblePeers) return
    reciprocalSnapshotJobs.compute(peer) { key, existing ->
      if (existing?.isActive == true) {
        existing
      } else {
        scope.launch {
          try {
            val available = withTimeoutOrNull(TRANSFER_INACTIVITY_TIMEOUT_MILLIS) {
              while (
                outboundSnapshots.containsKey(key) ||
                snapshotJobs[key]?.isActive == true
              ) {
                delay(50)
              }
              true
            } == true
            if (available && key in visiblePeers) {
              launchSnapshot(key)
            } else if (key in visiblePeers) {
              recoverStream(key, STATE_STREAM_NAME, "Reciprocal snapshot could not start")
            }
          } finally {
            reciprocalSnapshotJobs.remove(key, currentCoroutineContext()[Job])
          }
        }
      }
    }
  }


  private fun acceptKnownOperation(operation: BoardOperation): KnownOperationResult =
    knownLog.accept(operation)

  private fun mergeKnownOperations(operations: List<BoardOperation>): String? =
    knownLog.merge(operations)

  private fun stateMaterial(): KnownStateMaterial = knownLog.material()

  private suspend fun snapshotMaterial(): SnapshotMaterial = snapshotMaterialMutex.withLock {
    val capture = knownLog.capture()
    val state = capture.state
    val version = capture.version
    val cached = cachedSnapshotMaterial
    if (cached?.version == version) {
      cached
    } else {
      // Serialization and hashing happen outside the authoritative log lock. Reliable accepts can
      // keep advancing;
      // operations accepted after this immutable capture are sent independently and merged by the
      // receiver behind the snapshot, so a point-in-time snapshot remains correct.
      val bytes = WhiteboardProtocol.snapshotBytes(state)
      val material = SnapshotMaterial(
        version = version,
        state = state,
        bytes = bytes,
        byteDigest = WhiteboardProtocol.sha256(bytes),
      )
      if (knownLog.isCurrent(version)) cachedSnapshotMaterial = material
      material
    }
  }

  private suspend fun sendHello(peer: String) {
    val currentProfile = profile ?: return
    val material = stateMaterial()
    traceReadiness(
      "send hello peer=${peer.takeLast(6)} ops=${material.state.operations.size} " +
        "digest=${material.digest.tracePrefix()}",
    )
    enqueueOutbox(peer,
      WhiteboardProtocol.helloEnvelope(
        peerKey = localPeerKey,
        sequence = controlSequence.incrementAndGet(),
        lamport = material.state.latestStamp?.lamport ?: 0,
        profile = currentProfile,
        state = material.state,
        stateDigest = material.digest,
      ),
    )
  }

  private suspend fun sendSnapshot(peer: String) {
    val material = snapshotMaterial()
    val bytes = material.bytes
    val maxData = ((streams[StreamKey(peer, STATE_STREAM_NAME)]?.maxSendSize() ?: 49_152) - 1_024)
      .coerceAtLeast(1_024).coerceAtMost(48_128).toInt()
    val chunkCount = ((bytes.size.toLong() + maxData - 1L) / maxData).toInt()
    require(chunkCount in 1..MAX_TRANSFER_CHUNKS)
    // A transfer ID identifies one attempt, not its content. The digest is already carried
    // separately; reusing it here lets an old negative ACK cancel a newer retry of identical bytes.
    val transferId = UUID.randomUUID().toString()
    val outbound = OutboundSnapshot(transferId)
    outboundSnapshots[peer] = outbound
    outbound.timeoutJob = scope.launch {
      delay(MAX_SNAPSHOT_TRANSFER_LIFETIME_MILLIS)
      if (outboundSnapshots.remove(peer, outbound)) {
        recoverStream(peer, STATE_STREAM_NAME, "Snapshot acknowledgement timed out")
      }
    }
    updatePeer(peer) { it.copy(snapshotStatus = SnapshotStatus.Sending, snapshotProgress = 0f) }
    sendDirect(peer,
      baseEnvelope().setSnapshotBegin(
        SnapshotBegin.newBuilder()
          .setTransferId(transferId)
          .setChunkCount(chunkCount)
          .setByteCount(bytes.size.toLong())
          .setSha256(ByteString.copyFrom(material.byteDigest)),
      ).build().toByteArray(),
    )
    repeat(chunkCount) { index ->
      val offset = index * maxData
      val byteCount = minOf(maxData, bytes.size - offset)
      sendDirect(peer,
        baseEnvelope().setSnapshotChunk(
          SnapshotChunk.newBuilder()
            .setTransferId(transferId)
            .setIndex(index)
            .setData(ByteString.copyFrom(bytes, offset, byteCount)),
        ).build().toByteArray(),
      )
      updatePeer(peer) { it.copy(snapshotProgress = (index + 1).toFloat() / chunkCount) }
    }
    sendDirect(peer,
      baseEnvelope().setSnapshotEnd(SnapshotEnd.newBuilder().setTransferId(transferId)).build().toByteArray(),
    )
  }

  private suspend fun sendDirect(peer: String, bytes: ByteArray) {
    val key = StreamKey(peer, STATE_STREAM_NAME)
    val stream = streams[key] ?: error("State stream is not connected")
    peerSendMutexes.computeIfAbsent(peer) { Mutex() }.withLock {
      check(streams[key] === stream) { "State stream changed before send" }
      sendFramed(peer, stream, bytes)
    }
  }

  private suspend fun sendSnapshotAck(peer: String, transferId: String, accepted: Boolean, error: String) {
    enqueueOutbox(peer,
      baseEnvelope().setSnapshotAck(
        SnapshotAck.newBuilder().setTransferId(transferId).setAccepted(accepted).setError(error),
      ).build().toByteArray(),
    )
  }

  private fun baseEnvelope(): Envelope.Builder = Envelope.newBuilder()
    .setProtocolVersion(PROTOCOL_VERSION)
    .setBoardId(BOARD_ID)
    .setSenderPeerKey(localPeerKey)
    .setSenderSequence(controlSequence.incrementAndGet())

  private fun incompatible(peer: String, version: Int) {
    synchronized(readinessLock) {
      incompatiblePeerKeys += peer
      synchronizedPeerKeys -= peer
      awaitingInitialSyncPeerKeys -= peer
      readinessWaivedPeerKeys -= peer
    }
    clearInitialSyncTimeoutIfNoOutstanding()
    refreshEditingReadiness()
    diagnosticsState.update {
      it.copy(incompatiblePeers = it.incompatiblePeers + (peer to version))
    }
    mutableEvents.tryEmit(TransportEvent.IncompatiblePeer(peer, version))
    streams.keys.filter { it.peerKey == peer }.forEach { streams.remove(it)?.close() }
  }

  private fun updatePeer(peer: String, update: (PeerDiagnostics) -> PeerDiagnostics) =
    diagnosticsState.updatePeer(peer, update)

  private fun markInitialSyncComplete(peer: String) {
    synchronized(readinessLock) {
      synchronizedPeerKeys += peer
      readinessWaivedPeerKeys -= peer
      awaitingInitialSyncPeerKeys -= peer
    }
    clearInitialSyncTimeoutIfNoOutstanding()
    refreshEditingReadiness()
  }

  private fun clearInitialSyncTimeoutIfNoOutstanding() {
    synchronized(readinessLock) {
      if (hasOutstandingInitialSync(
        visiblePeers,
        awaitingInitialSyncPeerKeys,
        readinessWaivedPeerKeys,
        )) return
      diagnosticsState.update { current ->
        if (current.connectivityMessage?.startsWith("Initial nearby sync timed out") == true) {
          current.copy(connectivityMessage = null)
        } else {
          current
        }
      }
    }
  }

  private fun refreshEditingReadiness() {
    synchronized(readinessLock) {
      if (localOnly || (ready && awaitingInitialSyncPeerKeys.none { it in visiblePeers })) {
        initialReadinessResolved = true
        initialSyncTimeoutJob?.cancel()
        initialSyncTimeoutJob = null
        readinessGeneration.incrementAndGet()
      } else if (shouldStartInitialSyncDeadline(
          ready = ready,
          initialReadinessResolved = initialReadinessResolved,
          timeoutActive = initialSyncTimeoutJob?.isActive == true,
        )
      ) {
        val generation = readinessGeneration.incrementAndGet()
        initialSyncTimeoutJob = scope.launch {
          delay(INITIAL_SYNC_TIMEOUT_MILLIS)
          synchronized(readinessLock) {
            if (!initialReadinessResolved && readinessGeneration.get() == generation) {
              initialReadinessResolved = true
              readinessWaivedPeerKeys += awaitingInitialSyncPeerKeys
              awaitingInitialSyncPeerKeys.clear()
              initialSyncTimeoutJob = null
              diagnosticsState.update { current ->
                current.copy(
                  editingReady = true,
                  connectivityMessage =
                    "Initial nearby sync timed out. Editing is available, but an unreachable peer may be stale.",
                )
              }
            }
          }
        }
      }
      diagnosticsState.update { current ->
        current.copy(
          editingReady = localOnly || initialReadinessResolved,
        )
      }
    }
  }

  private fun refreshConnectionStatus(peer: String) {
    updatePeer(peer) {
      it.copy(
        liveConnected = streams.containsKey(StreamKey(peer, LIVE_STREAM_NAME)),
        stateConnected = streams.containsKey(StreamKey(peer, STATE_STREAM_NAME)),
      )
    }
  }

  private fun recordError(peer: String, error: Throwable) {
    updatePeer(peer) {
      it.copy(lastError = safeDiagnostic(error.message ?: error::class.java.simpleName))
    }
  }

  private fun recordError(peer: String, message: String) {
    updatePeer(peer) { it.copy(lastError = safeDiagnostic(message)) }
  }

  private fun traceReadiness(message: String) {
    if (BuildConfig.DEBUG) Log.d("DittoWhiteboard", message)
  }

  private fun ByteArray.tracePrefix(): String =
    take(4).joinToString(separator = "") { byte -> "%02x".format(byte) }

  private fun safeDiagnostic(message: String): String =
    message
      .take(MAX_DIAGNOSTIC_LENGTH * 2)
      .filter { it == '\n' || !it.isISOControl() }
      .take(MAX_DIAGNOSTIC_LENGTH)

  private fun safeDisplayName(value: String?): String? =
    value
      ?.filterNot(Char::isISOControl)
      ?.trim()
      ?.take(24)
      ?.takeIf(String::isNotBlank)

  private fun nearbyLifecycleActive(): Boolean =
    isNearbyLifecycleActive(started, foreground, nearbyNetworkingRunning, localOnly)

  override fun close() {
    // Ordered per SKILL.md: cancel connection/retry jobs, close streams, close acceptors,
    // cancel the remaining workers, then close Ditto last so no coroutine touches a closed endpoint.
    started = false
    lifecycleCoordinator.requestForeground(false)
    foregroundGeneration.incrementAndGet()
    connectJobs.values.forEach(Job::cancel)
    connectJobs.clear()
    outboxVisibility.disable {
      outboxJobs.values.forEach(Job::cancel)
      outboxJobs.clear()
      outboxes.values.forEach { it.close() }
      outboxes.clear()
      peerSendMutexes.clear()
    }
    presenceJob?.cancel()
    presenceJob = null
    snapshotJobs.values.forEach(Job::cancel)
    snapshotJobs.clear()
    reciprocalSnapshotJobs.values.forEach(Job::cancel)
    reciprocalSnapshotJobs.clear()
    snapshotRetryJobs.values.forEach(Job::cancel)
    snapshotRetryJobs.clear()
    outboundSnapshotRetryJobs.values.forEach(Job::cancel)
    outboundSnapshotRetryJobs.clear()
    helloCoalesceJobs.values.forEach(Job::cancel)
    helloCoalesceJobs.clear()
    pendingHellos.clear()
    snapshotRetryAttempts.clear()
    outboundSnapshotRetryAttempts.clear()
    snapshotRequested.clear()
    lastHelloNanos.clear()
    acceptOpenJobs.forEach(Job::cancel)
    acceptOpenJobs.clear()
    synchronized(readinessLock) {
      synchronizedPeerKeys.clear()
      awaitingInitialSyncPeerKeys.clear()
      readinessWaivedPeerKeys.clear()
      readinessDelayJob?.cancel()
      readinessDelayJob = null
      initialSyncTimeoutJob?.cancel()
      initialSyncTimeoutJob = null
      readinessGeneration.incrementAndGet()
    }
    outboundSnapshots.values.forEach { it.timeoutJob?.cancel() }
    outboundSnapshots.clear()
    recoveryJobs.values.forEach(Job::cancel)
    recoveryJobs.clear()
    synchronized(hydrationLock) {
      hydrations.values.forEach {
        it.timeoutJob?.cancel()
        it.finishJob?.cancel()
      }
      hydrations.clear()
    }
    chunkAssemblies.values.forEach { it.timeoutJob?.cancel() }
    chunkAssemblies.clear()
    streams.values.forEach(DittoStream::close)
    streams.clear()
    acceptors.forEach(DittoAcceptor::close)
    acceptors.clear()
    operationDispatcher.close()
    transportJob.cancel()
    ditto.sync.stop()
    ditto.close()
    diagnosticsState.update { it.copy(running = false) }
  }
}
