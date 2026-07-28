package com.ditto.whiteboard.transport

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
import com.ditto.whiteboard.domain.BoardReducer
import com.ditto.whiteboard.domain.BoardState
import com.ditto.whiteboard.domain.LivePreview
import com.ditto.whiteboard.domain.UserProfile
import com.ditto.whiteboard.protocol.LIVE_STREAM_NAME
import com.ditto.whiteboard.protocol.PROTOCOL_VERSION
import com.ditto.whiteboard.protocol.ProtocolDecodeResult
import com.ditto.whiteboard.protocol.STATE_STREAM_NAME
import com.ditto.whiteboard.protocol.SnapshotAssembler
import com.ditto.whiteboard.protocol.SnapshotAssemblyResult
import com.ditto.whiteboard.protocol.SnapshotTransfer
import com.ditto.whiteboard.protocol.VectorRelation
import com.ditto.whiteboard.protocol.WhiteboardProtocol
import com.ditto.whiteboard.protocol.compareStateVectors
import com.ditto.whiteboard.protocol.proto.Envelope
import com.ditto.whiteboard.protocol.proto.ReliableChunk
import com.ditto.whiteboard.protocol.proto.SnapshotAck
import com.ditto.whiteboard.protocol.proto.SnapshotBegin
import com.ditto.whiteboard.protocol.proto.SnapshotChunk
import com.ditto.whiteboard.protocol.proto.SnapshotEnd
import com.google.protobuf.ByteString
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val RELIABLE_OUTBOX_CAPACITY = 512

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
  val bufferedOperations: MutableList<BoardOperation> = mutableListOf(),
  var timeoutJob: Job? = null,
)

private data class ChunkAssembly(
  val assembler: SnapshotAssembler,
  val received: MutableSet<Int> = mutableSetOf(),
  val expectedCount: Int,
)

/**
 * The real [WhiteboardTransport], and the heart of this Data Streams demo.
 *
 * It binds two Data Streams topics on every peer and mirrors board traffic across the nearby mesh:
 *
 * - **`wb_live`** (Unreliable) carries fire-and-forget drawing previews — low latency, loss is fine.
 * - **`wb_state`** (Reliable) carries the operations that define the board (commits, erases, clears,
 *   profiles) plus late-join snapshots — ordered and never dropped.
 *
 * Connection ownership is driven by presence: to avoid opening a stream in both directions, the peer
 * with the lower public key calls [connect][com.ditto.kotlin.DittoDataStreamsEndpoint.connect] while
 * the higher-key peer only accepts via `bindTopic`. Inbound callbacks copy the payload and hand it to
 * a channel so the SDK's driver thread is never blocked. Reconnection uses bounded exponential
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
  private val permissionResult = CompletableDeferred<Boolean>()
  private val mutableEvents = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 256)
  override val events = mutableEvents.asSharedFlow()
  private val diagnosticsState = DiagnosticsState(
    TransportDiagnostics(localPeerKey = localPeerKey, mode = "Ditto nearby mesh"),
  )
  override val diagnostics = diagnosticsState.flow
  private val acceptors = mutableListOf<DittoAcceptor>()
  private val streams = ConcurrentHashMap<StreamKey, DittoStream>()
  private val connectJobs = ConcurrentHashMap<StreamKey, Job>()
  private val outboxes = ConcurrentHashMap<String, Channel<ByteArray>>()
  private val outboxJobs = ConcurrentHashMap<String, Job>()
  private val reliableInbound = Channel<Pair<String, ByteArray>>(Channel.UNLIMITED)
  private val liveInbound = Channel<Pair<String, ByteArray>>(128, BufferOverflow.DROP_OLDEST)
  private val pendingLive = Channel<LivePreview>(1, BufferOverflow.DROP_OLDEST)
  private val hydrations = ConcurrentHashMap<String, SnapshotHydration>()
  // At most one outbound snapshot transfer per peer may be in flight, so two overlapping hellos
  // can't launch concurrent sends that interleave BEGIN/CHUNK/END frames on the peer's single
  // ordered outbox and corrupt (or mutually reject) each other's transfer.
  private val snapshotJobs = ConcurrentHashMap<String, Job>()
  private val chunkAssemblies = ConcurrentHashMap<Pair<String, String>, ChunkAssembly>()
  private val txCounters = ConcurrentHashMap<String, AtomicLong>()
  private val rxCounters = ConcurrentHashMap<String, AtomicLong>()
  private val controlSequence = AtomicLong()
  private val liveSequence = AtomicLong()
  private val stateLock = Any()
  private var knownState = BoardState()
  // Read from IO connect/outbox coroutines while written from the presence/start coroutines,
  // so both need @Volatile to establish a happens-before edge across threads.
  @Volatile private var profile: UserProfile? = null
  @Volatile private var visiblePeers: Set<String> = emptySet()
  @Volatile private var started = false
  @Volatile private var ready = false

  init {
    val alreadyGranted = requiredPermissions.all {
      ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    if (alreadyGranted) permissionResult.complete(true)
    configureTransports(context)
    startWorkers()
  }

  override fun resolvePermissions(allGranted: Boolean) {
    if (!permissionResult.isCompleted) permissionResult.complete(allGranted)
    ditto.refreshPermissions()
    if (!allGranted) {
      diagnosticsState.update {
        it.copy(
          connectivityMessage = "Some nearby permissions were denied; available transports will continue in degraded mode.",
        )
      }
    }
  }

  override suspend fun start(profile: UserProfile) {
    this.profile = profile
    if (started) {
      publishMetadata(profile)
      return
    }
    val allGranted = permissionResult.await()
    if (!allGranted) resolvePermissions(false)
    ditto.deviceName = profile.displayName
    publishMetadata(profile)
    installAcceptors()
    observePresence()
    ditto.sync.start()
    started = true
    diagnosticsState.update { it.copy(running = true) }
    scope.launch {
      delay(1_000)
      ready = true
      streams.keys.filter { it.topic == STATE_STREAM_NAME }.forEach { sendHello(it.peerKey) }
    }
  }

  override suspend fun sendReliable(operation: BoardOperation) {
    synchronized(stateLock) { knownState = BoardReducer.apply(knownState, operation) }
    val bytes = WhiteboardProtocol.operationEnvelope(operation)
    // Fan out per peer on independent coroutines so one stalled peer's full or backed-up outbox can
    // only apply backpressure to its own enqueue — it can never head-of-line block reliable delivery
    // to every other healthy peer (shared-fate). Each peer keeps its own bounded-outbox backpressure,
    // and the drain loop bounds how long a visible-but-stream-less peer can hold its channel full.
    streams.keys.asSequence().filter { it.topic == STATE_STREAM_NAME }.map(StreamKey::peerKey).distinct().forEach { peer ->
      scope.launch { enqueueOutbox(peer, bytes) }
    }
  }

  override fun sendLive(preview: LivePreview) {
    pendingLive.trySend(preview.copy(points = preview.points.takeLast(32)))
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

  private fun installAcceptors() {
    if (acceptors.isNotEmpty()) return
    acceptors += bind(LIVE_STREAM_NAME, DittoReliability.Unreliable)
    acceptors += bind(STATE_STREAM_NAME, DittoReliability.Reliable)
  }

  private fun bind(topic: String, reliability: DittoReliability): DittoAcceptor =
    ditto.dataStreams.bindTopic(topic, reliability) { borrowedCandidate ->
      val peer = borrowedCandidate.peerKeyString()
      if (peer >= localPeerKey) return@bindTopic
      val candidate = borrowedCandidate.take()
      scope.launch(ioDispatcher) {
        try {
          val stream = candidate.use { owned ->
            owned.open { inbound -> enqueueInbound(peer, topic, inbound.payload().copyOf()) }
          }
          registerAndWait(StreamKey(peer, topic), stream)
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          recordError(peer, error)
        }
      }
    }

  private fun observePresence() {
    scope.launch {
      ditto.presence.observe().collect { graph ->
        val remote = graph.remotePeers.associateBy { it.peerKey }
        val connections = graph.remotePeers
          .flatMap { it.connections }
          .map { PresenceConnection(it.peer1, it.peer2, it.connectionType.name) }
          .toSet()
        visiblePeers = remote.keys
        val removed = connectJobs.keys.filter { it.peerKey !in visiblePeers }
        removed.forEach { key -> connectJobs.remove(key)?.cancel() }
        streams.keys.filter { it.peerKey !in visiblePeers }.forEach { key -> streams.remove(key)?.close() }
        // Tear down outboxes and partial-transfer state for peers that left, so their drain loops
        // don't spin forever and their bounded channels can never wedge a producer.
        (outboxes.keys + outboxJobs.keys).filter { it !in visiblePeers }.toSet().forEach { peer ->
          outboxJobs.remove(peer)?.cancel()
          outboxes.remove(peer)?.close()
        }
        chunkAssemblies.keys.filter { it.first !in visiblePeers }.forEach { chunkAssemblies.remove(it) }
        hydrations.keys.filter { it !in visiblePeers }.forEach { peer -> hydrations.remove(peer)?.timeoutJob?.cancel() }
        snapshotJobs.keys.filter { it !in visiblePeers }.forEach { peer -> snapshotJobs.remove(peer)?.cancel() }
        // Drop traffic counters for departed peers too, otherwise the 1s rate worker re-inserts a
        // ghost PeerDiagnostics for every peer that ever sent/received traffic (via updatePeer),
        // resurrecting it in the connected-people count long after it left the mesh.
        (txCounters.keys + rxCounters.keys).filter { it !in visiblePeers }.toSet().forEach { peer ->
          txCounters.remove(peer)
          rxCounters.remove(peer)
        }

        remote.values.forEach { peer ->
          val connectionNames = peer.connections.map { it.connectionType.name }.toSet()
          val metadata = peer.peerMetadata.toMap()
          val knownProfile = synchronized(stateLock) { knownState.profiles[peer.peerKey] }
          updatePeer(peer.peerKey) { current ->
            current.copy(
              displayName = knownProfile?.displayName ?: metadata["displayName"] as? String ?: peer.deviceName,
              colorArgb = knownProfile?.colorArgb ?: (metadata["colorArgb"] as? Number)?.toInt(),
              transports = connectionNames,
            )
          }
          if (localPeerKey < peer.peerKey) {
            ensureConnectJob(StreamKey(peer.peerKey, LIVE_STREAM_NAME), DittoReliability.Unreliable)
            ensureConnectJob(StreamKey(peer.peerKey, STATE_STREAM_NAME), DittoReliability.Reliable)
          }
        }
        diagnosticsState.update { current ->
          current.copy(
            peers = current.peers.filterKeys { it in visiblePeers },
            presenceConnections = connections,
          )
        }
      }
    }
  }

  private fun ensureConnectJob(key: StreamKey, reliability: DittoReliability) {
    if (streams.containsKey(key) || connectJobs[key]?.isActive == true) return
    connectJobs[key] = scope.launch(ioDispatcher) { connectLoop(key, reliability) }
  }

  private suspend fun connectLoop(key: StreamKey, reliability: DittoReliability) {
    var backoff = 500L
    while (scope.isActive && key.peerKey in visiblePeers && !streams.containsKey(key)) {
      try {
        val stream = ditto.dataStreams.connect(
          peer = key.peerKey,
          topic = key.topic,
          requestCompression = reliability == DittoReliability.Reliable,
          reliability = reliability,
          timeoutMs = 10_000,
        ) { candidate ->
          candidate.open { inbound -> enqueueInbound(key.peerKey, key.topic, inbound.payload().copyOf()) }
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
      if (key.peerKey in visiblePeers && !streams.containsKey(key)) {
        delay(backoff + Random.nextLong(0, min(250, backoff / 4) + 1))
        backoff = (backoff * 2).coerceAtMost(10_000)
      }
    }
  }

  private suspend fun registerAndWait(key: StreamKey, stream: DittoStream) {
    streams.put(key, stream)?.takeIf { it !== stream }?.close()
    if (key.topic == STATE_STREAM_NAME) {
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
    if (topic == LIVE_STREAM_NAME) liveInbound.trySend(peer to payload)
    else reliableInbound.trySend(peer to payload)
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
        when (val result = WhiteboardProtocol.decodeLive(payload, System.currentTimeMillis())) {
          is ProtocolDecodeResult.Compatible -> mutableEvents.emit(TransportEvent.LivePreviewReceived(result.value))
          is ProtocolDecodeResult.Incompatible -> incompatible(peer, result.version)
          is ProtocolDecodeResult.Invalid -> recordError(peer, IllegalArgumentException(result.reason))
        }
      }
    }
    scope.launch {
      for (preview in pendingLive) {
        val bytes = WhiteboardProtocol.encodeLive(preview, liveSequence.incrementAndGet())
        streams.filterKeys { it.topic == LIVE_STREAM_NAME }.forEach { (key, stream) ->
          if (bytes.size <= stream.maxSendSize()) {
            runCatching {
              stream.sendAndForget(bytes.copyOf())
              txCounters.computeIfAbsent(key.peerKey) { AtomicLong() }.incrementAndGet()
            }.onFailure { recordError(key.peerKey, it) }
          }
        }
        delay(34)
      }
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
    if (peer !in visiblePeers) return
    try {
      outbox(peer).send(bytes)
    } catch (closed: ClosedSendChannelException) {
      // Peer left between the visibility check and the send; drop the frame.
    }
  }

  private fun outbox(peer: String): Channel<ByteArray> = outboxes.computeIfAbsent(peer) {
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
                sendFramed(peer, stream, payload)
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
      DittoSendStatus.Failed -> throw IllegalStateException("Reliable send to ${peer.takeLast(6)} failed")
      DittoSendStatus.Cancelled -> {
        // Throws if the coroutine was cancelled (shutdown); otherwise falls through to the throw.
        currentCoroutineContext().ensureActive()
        throw IllegalStateException("Reliable send to ${peer.takeLast(6)} was cancelled by the SDK")
      }
      else -> txCounters.computeIfAbsent(peer) { AtomicLong() }.incrementAndGet()
    }
  }

  private suspend fun handleReliable(peer: String, payload: ByteArray) {
    when (val result = WhiteboardProtocol.decodeEnvelope(payload)) {
      is ProtocolDecodeResult.Incompatible -> incompatible(peer, result.version)
      is ProtocolDecodeResult.Invalid -> recordError(peer, IllegalArgumentException(result.reason))
      is ProtocolDecodeResult.Compatible -> {
        val envelope = result.value
        when (envelope.payloadCase) {
          Envelope.PayloadCase.HELLO -> handleHello(peer, envelope)
          Envelope.PayloadCase.OPERATION ->
            // Peer-supplied JSON; a malformed operation must be rejected, not thrown (mirrors decodeLive).
            when (val operation = runCatching { WhiteboardProtocol.decodeOperation(envelope) }.getOrNull()) {
              null -> recordError(peer, IllegalArgumentException("Malformed reliable operation"))
              else -> handleOperation(peer, operation)
            }
          Envelope.PayloadCase.SNAPSHOT_BEGIN -> beginHydration(peer, envelope)
          // Guard by transferId: a chunk from a stale/overlapping transfer must never be written
          // into a newer hydration's assembler (mirrors the transferId check finish() performs),
          // or it would corrupt the assembly at that index and doom the digest check.
          Envelope.PayloadCase.SNAPSHOT_CHUNK ->
            hydrations[peer]?.takeIf { it.transferId == envelope.snapshotChunk.transferId }?.let { hydration ->
              hydration.assembler.add(envelope.snapshotChunk.index, envelope.snapshotChunk.data.toByteArray())
              hydration.receivedChunks += envelope.snapshotChunk.index
              updatePeer(peer) {
                it.copy(snapshotProgress = hydration.receivedChunks.size.toFloat() / hydration.chunkCount)
              }
            }
          Envelope.PayloadCase.SNAPSHOT_END -> finishHydration(peer, envelope.snapshotEnd.transferId)
          Envelope.PayloadCase.SNAPSHOT_ACK -> updatePeer(peer) {
            it.copy(snapshotStatus = if (envelope.snapshotAck.accepted) "Acknowledged" else "Rejected", snapshotProgress = if (envelope.snapshotAck.accepted) 1f else it.snapshotProgress, lastError = envelope.snapshotAck.error.ifBlank { it.lastError })
          }
          Envelope.PayloadCase.RELIABLE_CHUNK -> handleChunk(peer, envelope.reliableChunk)
          Envelope.PayloadCase.PAYLOAD_NOT_SET, null -> Unit
        }
      }
    }
  }

  private suspend fun handleHello(peer: String, envelope: Envelope) {
    runCatching { WhiteboardProtocol.decodeProfile(envelope.hello.profileJson.toByteArray()) }.getOrNull()?.let { remoteProfile ->
      updatePeer(peer) { it.copy(displayName = remoteProfile.displayName, colorArgb = remoteProfile.colorArgb) }
    }
    val local = synchronized(stateLock) { knownState }
    val relation = compareStateVectors(local.highWaterMarks, envelope.hello.highWaterMarksMap)
    if (ready && local.operations.isNotEmpty() && relation in setOf(VectorRelation.LocalDominates, VectorRelation.Concurrent)) {
      // Launch the multi-chunk snapshot off the shared reliable worker so a slow/backed-up peer
      // can't head-of-line block inbound processing for every other peer. Serialize per peer: if a
      // transfer to this peer is still in flight, skip — enqueuing a second one concurrently would
      // interleave its frames with the first on the peer's ordered outbox. A skipped peer still
      // converges via the in-flight snapshot plus operations relayed through sendReliable, and any
      // receiver-side snapshot timeout re-hellos once the first transfer has drained.
      snapshotJobs.compute(peer) { key, existing ->
        if (existing?.isActive == true) {
          existing
        } else {
          scope.launch {
            try {
              sendSnapshot(key, local)
            } finally {
              snapshotJobs.remove(key, currentCoroutineContext()[Job])
            }
          }
        }
      }
    }
  }

  private suspend fun handleOperation(peer: String, operation: BoardOperation) {
    val hydration = hydrations[peer]
    if (hydration != null) {
      hydration.bufferedOperations += operation
      return
    }
    synchronized(stateLock) { knownState = BoardReducer.apply(knownState, operation) }
    if (operation is BoardOperation.ProfileUpdate) {
      updatePeer(peer) {
        it.copy(displayName = operation.profile.displayName, colorArgb = operation.profile.colorArgb)
      }
    }
    mutableEvents.emit(TransportEvent.ReliableOperationReceived(operation))
  }

  private fun beginHydration(peer: String, envelope: Envelope) {
    val begin = envelope.snapshotBegin
    // Reject a malformed SNAPSHOT_BEGIN before constructing the assembler, whose init require()s
    // chunkCount > 0 / byteCount >= 0 and would otherwise throw on peer-supplied garbage.
    if (begin.chunkCount <= 0 || begin.byteCount < 0) {
      recordError(peer, IllegalArgumentException("Invalid snapshot header from ${peer.takeLast(6)}"))
      return
    }
    val hydration = SnapshotHydration(
      transferId = begin.transferId,
      assembler = SnapshotAssembler(begin.transferId, begin.chunkCount, begin.byteCount, begin.sha256.toByteArray()),
      chunkCount = begin.chunkCount,
    )
    hydrations.put(peer, hydration)?.timeoutJob?.cancel()
    hydration.timeoutJob = scope.launch {
      delay(10_000)
      if (hydrations.remove(peer, hydration)) {
        updatePeer(peer) { it.copy(snapshotStatus = "Timed out", lastError = "Snapshot timed out after 10 seconds") }
        sendHello(peer)
      }
    }
    updatePeer(peer) { it.copy(snapshotStatus = "Receiving", snapshotProgress = 0f) }
  }

  private suspend fun finishHydration(peer: String, transferId: String) {
    val hydration = hydrations.remove(peer) ?: return
    hydration.timeoutJob?.cancel()
    when (val result = hydration.assembler.finish(transferId)) {
      is SnapshotAssemblyResult.Complete -> {
        val snapshotOperations = runCatching { WhiteboardProtocol.snapshotOperations(result.bytes) }.getOrElse { error ->
          sendSnapshotAck(peer, transferId, false, error.message ?: "Invalid snapshot")
          recordError(peer, error)
          return
        }
        synchronized(stateLock) {
          knownState = BoardReducer.merge(knownState, snapshotOperations + hydration.bufferedOperations)
        }
        mutableEvents.emit(TransportEvent.SnapshotMerged(snapshotOperations))
        hydration.bufferedOperations.forEach { mutableEvents.emit(TransportEvent.ReliableOperationReceived(it)) }
        updatePeer(peer) { it.copy(snapshotStatus = "Merged", snapshotProgress = 1f) }
        sendSnapshotAck(peer, transferId, true, "")
      }
      is SnapshotAssemblyResult.Rejected -> {
        updatePeer(peer) { it.copy(snapshotStatus = "Rejected", lastError = result.reason) }
        sendSnapshotAck(peer, transferId, false, result.reason)
      }
      SnapshotAssemblyResult.Pending -> Unit
    }
  }

  private suspend fun handleChunk(peer: String, chunk: ReliableChunk) {
    // Reject a malformed header before constructing the assembler, whose init require()s
    // chunkCount > 0 / byteCount >= 0 and would otherwise throw on peer-supplied garbage.
    if (chunk.chunkCount <= 0 || chunk.byteCount < 0) {
      recordError(peer, IllegalArgumentException("Invalid reliable chunk header from ${peer.takeLast(6)}"))
      return
    }
    val key = peer to chunk.transferId
    val assembly = chunkAssemblies.computeIfAbsent(key) {
      ChunkAssembly(
        SnapshotAssembler(chunk.transferId, chunk.chunkCount, chunk.byteCount, chunk.sha256.toByteArray()),
        expectedCount = chunk.chunkCount,
      )
    }
    // Only count indices the assembler accepts; an out-of-range index must not advance the received
    // set (which would let a valid slot stay null yet trip a premature, incomplete finish()).
    if (assembly.assembler.add(chunk.index, chunk.data.toByteArray()) is SnapshotAssemblyResult.Rejected) {
      recordError(peer, IllegalArgumentException("Reliable chunk index ${chunk.index} out of range"))
      return
    }
    assembly.received += chunk.index
    if (assembly.received.size == assembly.expectedCount) {
      chunkAssemblies.remove(key)
      val complete = assembly.assembler.finish(chunk.transferId)
      if (complete is SnapshotAssemblyResult.Complete) handleReliable(peer, complete.bytes)
      else if (complete is SnapshotAssemblyResult.Rejected) recordError(peer, IllegalArgumentException(complete.reason))
    }
  }

  private suspend fun sendHello(peer: String) {
    val currentProfile = profile ?: return
    val state = synchronized(stateLock) { knownState }
    enqueueOutbox(peer,
      WhiteboardProtocol.helloEnvelope(
        peerKey = localPeerKey,
        sequence = controlSequence.incrementAndGet(),
        lamport = state.operations.values.maxOfOrNull { it.stamp.lamport } ?: 0,
        profile = currentProfile,
        ready = ready,
        state = state,
      ),
    )
  }

  private suspend fun sendSnapshot(peer: String, state: BoardState) {
    val bytes = WhiteboardProtocol.snapshotBytes(state)
    val maxData = ((streams[StreamKey(peer, STATE_STREAM_NAME)]?.maxSendSize() ?: 49_152) - 1_024)
      .coerceAtLeast(1_024).coerceAtMost(48_128).toInt()
    val transfer = SnapshotTransfer.create(bytes, maxData)
    updatePeer(peer) { it.copy(snapshotStatus = "Sending", snapshotProgress = 0f) }
    enqueueOutbox(peer,
      baseEnvelope().setSnapshotBegin(
        SnapshotBegin.newBuilder()
          .setTransferId(transfer.id)
          .setChunkCount(transfer.chunks.size)
          .setByteCount(bytes.size.toLong())
          .setSha256(ByteString.copyFrom(transfer.digest)),
      ).build().toByteArray(),
    )
    transfer.chunks.forEachIndexed { index, chunk ->
      enqueueOutbox(peer,
        baseEnvelope().setSnapshotChunk(
          SnapshotChunk.newBuilder().setTransferId(transfer.id).setIndex(index).setData(ByteString.copyFrom(chunk)),
        ).build().toByteArray(),
      )
      updatePeer(peer) { it.copy(snapshotProgress = (index + 1).toFloat() / transfer.chunks.size) }
    }
    enqueueOutbox(peer,
      baseEnvelope().setSnapshotEnd(SnapshotEnd.newBuilder().setTransferId(transfer.id)).build().toByteArray(),
    )
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
    diagnosticsState.update {
      it.copy(incompatiblePeers = it.incompatiblePeers + (peer to version))
    }
    mutableEvents.tryEmit(TransportEvent.IncompatiblePeer(peer, version))
    streams.keys.filter { it.peerKey == peer }.forEach { streams.remove(it)?.close() }
  }

  private fun updatePeer(peer: String, update: (PeerDiagnostics) -> PeerDiagnostics) =
    diagnosticsState.updatePeer(peer, update)

  private fun refreshConnectionStatus(peer: String) {
    updatePeer(peer) {
      it.copy(
        liveConnected = streams.containsKey(StreamKey(peer, LIVE_STREAM_NAME)),
        stateConnected = streams.containsKey(StreamKey(peer, STATE_STREAM_NAME)),
      )
    }
  }

  private fun recordError(peer: String, error: Throwable) {
    updatePeer(peer) { it.copy(lastError = error.message ?: error::class.java.simpleName) }
  }

  override fun close() {
    // Ordered per SKILL.md: cancel connection/retry jobs, close streams, close acceptors,
    // cancel the remaining workers, then close Ditto last so no coroutine touches a closed endpoint.
    started = false
    connectJobs.values.forEach(Job::cancel)
    connectJobs.clear()
    outboxJobs.values.forEach(Job::cancel)
    outboxJobs.clear()
    snapshotJobs.values.forEach(Job::cancel)
    snapshotJobs.clear()
    streams.values.forEach(DittoStream::close)
    streams.clear()
    acceptors.forEach(DittoAcceptor::close)
    acceptors.clear()
    outboxes.values.forEach { it.close() }
    outboxes.clear()
    transportJob.cancel()
    ditto.sync.stop()
    ditto.close()
    diagnosticsState.update { it.copy(running = false) }
  }
}
