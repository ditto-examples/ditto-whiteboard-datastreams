package com.ditto.whiteboard.transport

import com.ditto.whiteboard.domain.BoardOperation
import com.ditto.whiteboard.domain.LivePreview
import com.ditto.whiteboard.domain.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CompletableDeferred

/**
 * The seam between the app and the network. [DittoWhiteboardTransport] is the real implementation
 * over Ditto Data Streams; [InMemoryWhiteboardTransport] is a local-only fallback used when
 * credentials or the SDK are unavailable. Callers observe [events] and [diagnostics] and push work
 * in with [sendReliable] (ordered, durable board operations) and [sendLive] (lossy previews).
 */
interface WhiteboardTransport : AutoCloseable {
  val localPeerKey: String
  val events: Flow<TransportEvent>
  val diagnostics: StateFlow<TransportDiagnostics>
  val requiredPermissions: List<String> get() = emptyList()

  suspend fun start(profile: UserProfile)
  /** Atomically reserves the operation in the authoritative reconciliation log. */
  suspend fun sendReliable(operation: BoardOperation): Boolean
  fun sendLive(preview: LivePreview)
  fun resolvePermissions(allGranted: Boolean) = Unit
  /** Starts or pauses nearby networking while retaining in-memory board state. */
  fun setForeground(isForeground: Boolean) = Unit
}

sealed interface TransportEvent {
  data class ReliableOperationReceived(
    val operation: BoardOperation,
    val prepared: CompletableDeferred<Boolean>? = null,
    val committed: CompletableDeferred<Boolean>? = null,
    val applied: CompletableDeferred<Boolean>? = null,
  ) : TransportEvent
  data class LivePreviewReceived(val preview: LivePreview) : TransportEvent
  data class SnapshotMerged(
    val operations: List<BoardOperation>,
    val prepared: CompletableDeferred<Boolean>? = null,
    val committed: CompletableDeferred<Boolean>? = null,
    val applied: CompletableDeferred<Boolean>? = null,
  ) : TransportEvent
  data class IncompatiblePeer(val peerKey: String, val protocolVersion: Int) : TransportEvent
}

data class PeerDiagnostics(
  val peerKey: String,
  val displayName: String? = null,
  val colorArgb: Int? = null,
  val transports: Set<String> = emptySet(),
  val liveConnected: Boolean = false,
  val stateConnected: Boolean = false,
  val snapshotStatus: SnapshotStatus = SnapshotStatus.Idle,
  val snapshotProgress: Float = 0f,
  val transmitMessagesPerSecond: Double = 0.0,
  val receiveMessagesPerSecond: Double = 0.0,
  val lastError: String? = null,
)

enum class SnapshotStatus {
  Idle,
  /** Offer refused because the remote peer's single hydration slot is busy; waiting to re-offer. */
  Queued,
  Receiving,
  Merged,
  Rejected,
  Acknowledged,
  Sending,
}

enum class TransportMode {
  LocalPreview,
  NearbyMesh,
}

data class PresenceConnection(
  val peer1: String,
  val peer2: String,
  val transport: String,
)

data class TransportDiagnostics(
  val localPeerKey: String = "local",
  val running: Boolean = false,
  /** False only while a newly discovered peer's initial reconciliation is unfinished. */
  val editingReady: Boolean = true,
  val mode: TransportMode = TransportMode.LocalPreview,
  val connectivityMessage: String? = null,
  val peers: Map<String, PeerDiagnostics> = emptyMap(),
  val presenceConnections: Set<PresenceConnection> = emptySet(),
  val incompatiblePeers: Map<String, Int> = emptyMap(),
)
