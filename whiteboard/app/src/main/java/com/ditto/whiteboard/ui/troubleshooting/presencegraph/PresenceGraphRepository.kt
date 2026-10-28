package com.ditto.whiteboard.ui.troubleshooting.presencegraph

import android.os.Build
import android.util.Log
import com.ditto.kotlin.Ditto
import com.ditto.kotlin.DittoConnectionType
import com.ditto.kotlin.DittoPeer
import com.ditto.kotlin.DittoPeerOs
import com.ditto.kotlin.DittoPresenceGraph
import com.ditto.kotlin.serialization.DittoJsonSerializable
import com.ditto.whiteboard.BuildConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Observes the raw Ditto presence graph for the presence viewer, enriching each update
 * with `system:data_sync_info` commit progress. A minimal port of Edge Studio's
 * `SystemRepositoryImpl.updatePresence`: both-sides edge aggregation (the local peer
 * is authoritative for edges only it advertises, notably multicast), orphan-peer
 * filtering in the full-mesh projection, Ditto Server synthesis from sync metrics, and
 * best-effort sync-metrics enrichment that can never stall the presence pipeline.
 *
 * Unlike Edge Studio there is no transport-config filtering: the whiteboard has no
 * per-transport settings UI feeding the presence layer (its debug transport toggles
 * apply at startup and are not read here).
 */
class PresenceGraphRepository(
  val ditto: Ditto,
  coroutineScope: CoroutineScope,
  /**
   * Dispatcher for the `system:data_sync_info` enrichment query (see [metricsScope]).
   * Injectable so tests can pin the pipeline onto a controllable dispatcher.
   */
  private val metricsDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
  companion object {
    private const val TAG = "PresenceGraphRepository"
    private const val FIELD_IS_DITTO_SERVER = "is_ditto_server"
    private const val FIELD_DOCUMENTS = "documents"
    private const val FIELD_SYNC_SESSION_STATUS = "sync_session_status"
    private const val FIELD_SYNCED_UP_TO_LOCAL_COMMIT_ID = "synced_up_to_local_commit_id"
    private const val FIELD_LAST_UPDATE_RECEIVED_TIME = "last_update_received_time"
    private const val SYNC_STATUS_NOT_CONNECTED = "Not Connected"

    /**
     * How long a presence update will wait on the `system:data_sync_info` enrichment
     * query before publishing without it. See [fetchSyncMetricsBestEffort].
     */
    private const val SYNC_METRICS_TIMEOUT_MS = 2_000L

    private const val SYNC_METRICS_QUERY = "SELECT * FROM system:data_sync_info"
  }

  private val mutablePeers = MutableStateFlow<List<SyncStatusInfo>>(emptyList())
  private val mutableLocalPeer = MutableStateFlow<LocalPeerInfo?>(null)
  private val mutableMeshTopology = MutableStateFlow(MeshTopology.Empty)

  val state: StateFlow<PresenceGraphUiState> = combine(
    mutableLocalPeer,
    mutablePeers,
    mutableMeshTopology,
  ) { local, remote, mesh ->
    PresenceGraphUiState.Active(localPeer = local, remotePeers = remote, meshTopology = mesh)
  }.stateIn(coroutineScope, SharingStarted.Eagerly, PresenceGraphUiState.Initializing)

  /**
   * Scope for the `system:data_sync_info` enrichment query.
   *
   * Deliberately NOT tied to the collecting coroutine. `DittoStore.execute` is built on
   * `suspendCoroutine`, so it is **not cancellable**: cancelling the coroutine that is
   * waiting on it does not abort the native query, it only guarantees the delivered
   * `DittoQueryResult` is dropped instead of closed. The underlying read transaction then
   * stays open forever, which blocks `Ditto.close()`. Letting these queries always run
   * to completion is what keeps that from happening; the presence pipeline protects
   * itself with a timeout instead (see [fetchSyncMetricsBestEffort]).
   */
  private val metricsScope = CoroutineScope(SupervisorJob() + metricsDispatcher)

  /** In-flight enrichment query, reused so a slow one can't stack up per presence tick. */
  @Volatile
  private var metricsJob: Deferred<Map<String, JSONObject>>? = null

  /** Last successful enrichment result, used while a query is slow or failing. */
  @Volatile
  private var lastSyncMetrics: Map<String, JSONObject> = emptyMap()

  private val observeJob = coroutineScope.launch {
    ditto.presence.observe().collect { graph ->
      updatePresence(graph)
    }
  }

  /**
   * Fetches the `system:data_sync_info` enrichment (commit IDs, cloud-server peers) without
   * ever letting it stall the presence pipeline.
   *
   * The query runs on [metricsScope] — detached, so it always completes and closes its read
   * transaction — and is awaited with a timeout. `await()` *is* cancellable even though
   * `execute` is not, so a wedged query costs one orphaned coroutine rather than a dead
   * presence screen. This matters because `presence.observe()` is collected sequentially:
   * one unbounded suspend here stops every future topology update from ever being processed.
   *
   * On timeout or failure we fall back to the last good result, so peer cards keep their
   * commit IDs instead of flickering to blank.
   */
  private suspend fun fetchSyncMetricsBestEffort(): Map<String, JSONObject> {
    val inFlight = metricsJob?.takeIf { it.isActive }
    val job = inFlight ?: metricsScope.async { querySyncMetrics() }.also { metricsJob = it }

    val fresh = withTimeoutOrNull(SYNC_METRICS_TIMEOUT_MS) {
      runCatching { job.await() }.getOrNull()
    }
    if (fresh != null) {
      lastSyncMetrics = fresh
      return fresh
    }
    if (BuildConfig.DEBUG) {
      Log.w(
        TAG,
        "system:data_sync_info did not return within ${SYNC_METRICS_TIMEOUT_MS}ms — " +
          "publishing presence with the previous sync metrics",
      )
    }
    return lastSyncMetrics
  }

  private suspend fun querySyncMetrics(): Map<String, JSONObject> {
    val syncMetrics = mutableMapOf<String, JSONObject>()
    runCatching {
      ditto.store.execute(SYNC_METRICS_QUERY) { result ->
        for (item in result.items) {
          val json = runCatching { JSONObject(item.jsonString()) }.getOrNull() ?: continue
          val peerId = json.optString("_id").takeIf { it.isNotBlank() } ?: continue
          syncMetrics[peerId] = json
        }
      }
    }.onFailure { e ->
      if (BuildConfig.DEBUG) {
        Log.w(TAG, "system:data_sync_info query failed — commit IDs unavailable", e)
      }
    }
    return syncMetrics
  }

  private suspend fun updatePresence(graph: DittoPresenceGraph) {
    // 1. Query sync metrics — best effort, never blocks the presence pipeline.
    val syncMetrics = fetchSyncMetricsBestEffort()

    val localPeerKey = graph.localPeer.peerKey

    // Peers the LOCAL side advertises a link to. See the filter below for why both
    // sides must be consulted rather than the remote peer's `connections` alone.
    val locallyAdvertisedPeerKeys: Set<String> = graph.localPeer.connections
      .flatMap { conn -> listOf(conn.peer1, conn.peer2) }
      .filter { it.isNotBlank() && it != localPeerKey }
      .toSet()

    // 2. Deduplicate remote peers by peerKey, then filter to directly connected peers only.
    // presenceGraph.remotePeers returns the full mesh topology (all peers in the network,
    // including multihop peers). A peer is "directly connected" if the local device's peer
    // key is an endpoint of at least one of its connections.
    val deduped = graph.remotePeers
      .groupBy { it.peerKey }
      .mapValues { (_, peers) ->
        peers.maxByOrNull { it.dittoSdkVersion != null } ?: peers.first()
      }
      .values
      .filter { peer ->
        // Only directly connected peers — the local peer must be an endpoint of at
        // least one connection. Both sides are consulted: Ditto usually reports an
        // undirected edge from both endpoints, but the local peer is authoritative
        // for edges attached to this process, and a link only the local side
        // advertises (notably multicast) is invisible in the remote peer's own
        // `connections`.
        peer.peerKey in locallyAdvertisedPeerKeys ||
          peer.connections.any { conn -> conn.peer1 == localPeerKey || conn.peer2 == localPeerKey }
      }

    val processedIds = mutableSetOf<String>()

    // 3. Map presence peers with merged sync metrics.
    val remotePeers = deduped.map { peer ->
      processedIds.add(peer.peerKey)
      peer.toSyncStatusInfo(syncMetrics[peer.peerKey], localPeerKey)
    }.toMutableList()

    // 4. Add Cloud Server peers from DQL not in the presence graph.
    for ((peerId, metrics) in syncMetrics) {
      if (peerId in processedIds) continue
      if (!metrics.optBoolean(FIELD_IS_DITTO_SERVER, false)) continue
      val docs = metrics.optJSONObject(FIELD_DOCUMENTS)
      val status = docs?.optString(FIELD_SYNC_SESSION_STATUS)
      if (status == SYNC_STATUS_NOT_CONNECTED) continue
      remotePeers.add(
        SyncStatusInfo(
          peerId = peerId,
          isDittoServer = true,
          deviceName = null,
          osInfo = PeerOS.Unknown,
          dittoSdkVersion = null,
          syncedUpToLocalCommitId = docs?.optLongOrNull(FIELD_SYNCED_UP_TO_LOCAL_COMMIT_ID),
          lastUpdateReceivedTime = docs?.optLongOrNull(FIELD_LAST_UPDATE_RECEIVED_TIME)?.toDouble(),
        ),
      )
    }

    // 5a. Build the full mesh topology — every connection the SDK knows about,
    //     plus every discovered peer that participates in at least one of those
    //     edges. The viewer needs this when the user toggles "Direct Connected"
    //     off; the direct-only projection keeps using the filtered list above.
    val allPeersDeduped = graph.remotePeers
      .groupBy { it.peerKey }
      .mapValues { (_, peers) ->
        peers.maxByOrNull { it.dittoSdkVersion != null } ?: peers.first()
      }
      .values
    val seenEdgeKeys = mutableSetOf<String>()
    val meshEdgeList = buildList {
      // Ditto usually reports the same undirected edge from both endpoints, but
      // the local peer is the authoritative source for edges attached to this
      // process. Aggregate it too so a transport (notably multicast) is not
      // lost when only the local side advertises the edge.
      for (peer in listOf(graph.localPeer) + allPeersDeduped) {
        for (conn in peer.connections) {
          val p1 = conn.peer1
          val p2 = conn.peer2
          if (p1.isBlank() || p2.isBlank()) continue
          val sortedPair = listOf(p1, p2).sorted()
          val key = "${sortedPair[0]}_${sortedPair[1]}_${conn.connectionType}"
          if (!seenEdgeKeys.add(key)) continue
          add(MeshEdge(p1, p2, conn.connectionType.toConnectionType()))
        }
      }
    }
    // Orphan filter: remote peers appearing in no aggregated edge are dropped —
    // otherwise they float as pills on the outermost ring in the sync stop→start
    // window. The local peer is always shown regardless — it's added by the graph
    // model builder, not from this list.
    val meshPeerList = filterOrphanMeshPeers(
      peers = allPeersDeduped.map { peer -> peer.toMeshPeer() },
      edges = meshEdgeList,
    )

    // 5b. Publish all derived flows.
    mutablePeers.value = remotePeers
    mutableMeshTopology.value = MeshTopology(
      localPeerKey = localPeerKey,
      peers = meshPeerList,
      edges = meshEdgeList,
    )
    mutableLocalPeer.value = LocalPeerInfo(
      peerId = graph.localPeer.peerKey,
      // The transport sets `ditto.deviceName` to the profile display name, which is
      // the most useful label; fall back to the hardware model before session start.
      deviceName = graph.localPeer.deviceName.takeIf { it.isNotBlank() }
        ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
      sdkLanguage = "Kotlin",
      sdkPlatform = "Android",
      sdkVersion = graph.localPeer.dittoSdkVersion ?: "Unknown",
      // The Kotlin SDK exposes this as `isConnectedToDittoServer`; the iOS SDK
      // calls the same flag `isConnectedToDittoCloud`. Both mean "is this local
      // device currently linked to the hosted Big Peer / Ditto Cloud service?".
      isCloudConnected = graph.localPeer.isConnectedToDittoServer,
    )
  }

  private fun DittoPeer.toSyncStatusInfo(
    metrics: JSONObject? = null,
    localPeerKey: String,
  ): SyncStatusInfo {
    val docs = metrics?.optJSONObject(FIELD_DOCUMENTS)
    return SyncStatusInfo(
      peerId = peerKey,
      isDittoServer = metrics?.optBoolean(FIELD_IS_DITTO_SERVER, false) ?: false,
      deviceName = deviceName.takeIf { it.isNotBlank() },
      osInfo = os?.toPeerOS() ?: PeerOS.Unknown,
      dittoSdkVersion = dittoSdkVersion?.takeIf { it.isNotBlank() },
      connections = connections
        .filter { conn -> conn.peer1 == localPeerKey || conn.peer2 == localPeerKey }
        .distinctBy { conn -> conn.connectionType }
        .map { conn ->
          PeerConnectionInfo(
            id = conn.id,
            type = conn.connectionType.toConnectionType(),
          )
        },
      // ObjectValue.toString() is Kotlin map syntax ("{role=my kiosk}"), NOT JSON —
      // verified: org.json.JSONObject rejects it. The raw string is kept only for
      // verbatim display; never derive a key count from it, use the counts below.
      peerMetadata = peerMetadata.takeIf { it.isNotEmpty() }?.toString(),
      peerMetadataKeyCount = peerMetadata.keyCountOrZero(),
      identityServiceMetadata = identityServiceMetadata.takeIf { it.isNotEmpty() }?.toString(),
      identityServiceMetadataKeyCount = identityServiceMetadata.keyCountOrZero(),
      syncedUpToLocalCommitId = docs?.optLongOrNull(FIELD_SYNCED_UP_TO_LOCAL_COMMIT_ID),
      lastUpdateReceivedTime = docs?.optLongOrNull(FIELD_LAST_UPDATE_RECEIVED_TIME)?.toDouble(),
    )
  }

  /**
   * Full `DittoPeer` projection for the mesh view. Everything here is available for
   * INDIRECT peers as well — the presence graph reports the same fields regardless of
   * whether we can reach the peer. Sync progress is deliberately absent: it comes from
   * `system:data_sync_info`, which only has rows for peers we actually receive data
   * from.
   *
   * Metadata is reduced to (raw string, top-level key count) here rather than in the UI
   * so the SDK's serialization types stay out of the composables, and so the key
   * count is computed once per presence update instead of once per recomposition.
   */
  private fun DittoPeer.toMeshPeer(): MeshPeer {
    // NOT `takeIf { !it.isNull }`: isNull asks "is this the JSON literal null?", so
    // it is false for an ObjectValue even when the object is empty — verified
    // against the SDK (empty ObjectValue: isNull=false, isEmpty=true, toString="{}").
    // Using it would give every peer that never set metadata a non-blank "{}" and
    // the card would report metadata "present" for all of them.
    val peerMeta = peerMetadata.takeIf { it.isNotEmpty() }
    val identityMeta = identityServiceMetadata.takeIf { it.isNotEmpty() }
    return MeshPeer(
      peerKey = peerKey,
      deviceName = deviceName.takeIf { it.isNotBlank() },
      os = os?.toPeerOS() ?: PeerOS.Unknown,
      dittoSdkVersion = dittoSdkVersion?.takeIf { it.isNotBlank() },
      isConnectedToDittoServer = isConnectedToDittoServer,
      isCompatible = isCompatible,
      peerMetadata = peerMeta?.toString(),
      peerMetadataKeyCount = peerMeta?.keyCountOrZero() ?: 0,
      identityServiceMetadata = identityMeta?.toString(),
      identityServiceMetadataKeyCount = identityMeta?.keyCountOrZero() ?: 0,
    )
  }

  /** Top-level key count, 0 if the SDK object can't be converted (never throws). */
  private fun DittoJsonSerializable.ObjectValue.keyCountOrZero(): Int =
    runCatching { toMap().size }.getOrDefault(0)

  private fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null

  private fun DittoPeerOs.toPeerOS(): PeerOS = when (this) {
    DittoPeerOs.Ios, DittoPeerOs.Tvos -> PeerOS.iOS
    DittoPeerOs.Android -> PeerOS.Android
    DittoPeerOs.MacOS -> PeerOS.MacOS
    DittoPeerOs.Linux -> PeerOS.Linux
    DittoPeerOs.Windows -> PeerOS.Windows
    DittoPeerOs.Generic -> PeerOS.Unknown
  }

  private fun DittoConnectionType.toConnectionType(): ConnectionType = when (this) {
    DittoConnectionType.Bluetooth -> ConnectionType.Bluetooth
    DittoConnectionType.AccessPoint -> ConnectionType.LAN
    DittoConnectionType.Multicast -> ConnectionType.Multicast
    DittoConnectionType.P2PWiFi -> ConnectionType.P2PWiFi
    DittoConnectionType.WebSocket -> ConnectionType.WebSocket
  }
}

/**
 * Keep only peers that participate in at least one mesh edge (VS Code extension
 * `buildPresenceGraphView` pass 2). Drops orphan peers the SDK has discovered
 * (mDNS/BLE) but holds no current connection to — most visible in the window
 * right after `sync.stop()` → `sync.start()`, when transports stay alive across
 * the toggle but sync sessions don't. Edge participation (not an empty
 * own-connections list) is the criterion so a peer that only appears as peer2
 * in another peer's connection list is still drawn.
 */
internal fun filterOrphanMeshPeers(peers: List<MeshPeer>, edges: List<MeshEdge>): List<MeshPeer> {
  val peersInAnyEdge = HashSet<String>(edges.size * 2)
  for (edge in edges) {
    peersInAnyEdge.add(edge.peer1)
    peersInAnyEdge.add(edge.peer2)
  }
  return peers.filter { it.peerKey in peersInAnyEdge }
}
