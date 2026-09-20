package com.ditto.whiteboard.ui.troubleshooting.presencegraph

/**
 * Presence-graph viewer models, ported from Edge Studio (`domain/model/MeshTopology.kt`,
 * `SyncStatusInfo.kt`, `LocalPeerInfo.kt`). Kept Compose-free so the graph projection and the
 * search rules stay unit-testable on the JVM.
 */

data class LocalPeerInfo(
  val peerId: String,
  val deviceName: String,
  val sdkLanguage: String,
  val sdkPlatform: String,
  val sdkVersion: String,
  val isCloudConnected: Boolean = false,
)

data class SyncStatusInfo(
  val peerId: String,
  val isDittoServer: Boolean = false,
  val deviceName: String?,
  val osInfo: PeerOS = PeerOS.Unknown,
  val dittoSdkVersion: String?,
  val connections: List<PeerConnectionInfo> = emptyList(),
  val peerMetadata: String? = null,
  /**
   * Top-level key count of [peerMetadata]. Carried from the SDK's typed object rather
   * than derived from the string: `ObjectValue.toString()` emits Kotlin map syntax,
   * not JSON, so parsing it back always fails.
   */
  val peerMetadataKeyCount: Int = 0,
  val identityServiceMetadata: String? = null,
  val identityServiceMetadataKeyCount: Int = 0,
  val syncedUpToLocalCommitId: Long? = null,
  val lastUpdateReceivedTime: Double? = null,
)

data class PeerConnectionInfo(
  val id: String,
  val type: ConnectionType,
)

enum class ConnectionType(val displayName: String) {
  Bluetooth("Bluetooth"),
  LAN("LAN"),
  P2PWiFi("P2P WiFi"),

  /** Reliable UDP multicast transport (beta, Ditto SDK 5.1.0). */
  Multicast("Multicast"),
  WebSocket("WebSocket"),
  Unknown("Unknown"),
}

enum class PeerOS(val displayName: String) {
  iOS("iOS"),
  Android("Android"),
  MacOS("macOS"),
  Linux("Linux"),
  Windows("Windows"),
  Unknown("Unknown"),
}

/**
 * Snapshot of the full presence-graph mesh, captured directly from the SDK with NO
 * direct-connection filtering. Used by the Presence Viewer when "Direct Connected
 * Only" is toggled off — that mode reveals every peer Ditto knows about plus every
 * connection between them.
 */
data class MeshTopology(
  val localPeerKey: String,
  val peers: List<MeshPeer>,
  val edges: List<MeshEdge>,
) {
  companion object {
    val Empty: MeshTopology = MeshTopology(
      localPeerKey = "",
      peers = emptyList(),
      edges = emptyList(),
    )
  }
}

/**
 * Projection of a peer in the raw mesh. Everything here comes from `DittoPeer` and is
 * therefore available for **indirect** peers too. Sync progress deliberately does NOT
 * live here: it comes from `system:data_sync_info`, a local table with no rows for
 * indirect peers; the viewer joins it in separately via the direct-peers list.
 */
data class MeshPeer(
  val peerKey: String,
  val deviceName: String?,
  val os: PeerOS = PeerOS.Unknown,
  val dittoSdkVersion: String? = null,
  /** Whether THIS peer has a Ditto Cloud link — true even for peers we can't reach. */
  val isConnectedToDittoServer: Boolean = false,
  val isCompatible: Boolean? = null,
  /** Raw peer metadata string, or null when empty. Capped at 4 KB by the SDK. */
  val peerMetadata: String? = null,
  /** Top-level key count of [peerMetadata] — the card shows a badge, not the blob. */
  val peerMetadataKeyCount: Int = 0,
  /** Raw identity-service metadata string (set by the auth webhook), or null when empty. */
  val identityServiceMetadata: String? = null,
  val identityServiceMetadataKeyCount: Int = 0,
)

/**
 * One mesh edge with full endpoint identities. Deduplicated by sorted (peer1, peer2, type)
 * — the SDK returns A→B and B→A as separate `DittoConnection` instances with the same
 * type, which the repository collapses before exposing.
 */
data class MeshEdge(
  val peer1: String,
  val peer2: String,
  val type: ConnectionType,
)

/**
 * UI-facing peers state for the presence graph viewer — the whiteboard's counterpart of
 * Edge Studio's `PeersUiState`.
 */
sealed interface PresenceGraphUiState {
  data object Initializing : PresenceGraphUiState
  data class Active(
    val localPeer: LocalPeerInfo?,
    val remotePeers: List<SyncStatusInfo>,
    val meshTopology: MeshTopology = MeshTopology.Empty,
  ) : PresenceGraphUiState
}
