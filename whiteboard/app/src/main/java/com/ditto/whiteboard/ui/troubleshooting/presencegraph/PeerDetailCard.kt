package com.ditto.whiteboard.ui.troubleshooting.presencegraph

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ditto.whiteboard.R
import com.ditto.whiteboard.transport.PeerDiagnostics
import com.ditto.whiteboard.transport.SnapshotStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fixed card width. Screen-space, so this is the real on-screen size at any camera zoom.
 */
internal val PEER_DETAIL_CARD_WIDTH = 260.dp

/**
 * Expanded detail for one peer in the presence graph.
 *
 * Renders every field the SDK exposes for a peer. All of it except the sync rows is
 * available for peers the local device cannot reach, which is most of a focus orbit —
 * the orbit is the *focused* peer's neighbourhood, not ours.
 *
 * Rows are always present, even when empty. A missing value shows an explicit reason
 * rather than disappearing: the absence is itself the information, and a fixed row set
 * keeps the card from resizing as the SDK fills fields in (`os` in particular is
 * documented as learned gradually).
 *
 * [whiteboard] carries the whiteboard-specific stream diagnostics (wb_live/wb_state,
 * snapshot state, traffic) for peers that run this app; it is null for every other peer
 * in the mesh and for the synthetic cloud node.
 */
@Composable
internal fun PeerDetailCard(
  node: PeerNode,
  maxHeightPx: Float,
  onFocusPeer: (() -> Unit)?,
  modifier: Modifier = Modifier,
  whiteboard: PeerDiagnostics? = null,
) {
  val detail = node.detail
  val density = LocalDensity.current
  // Cap to the viewport and scroll the overflow. At large system font scales every
  // labelSmall row grows, and without this the bottom of the card — the sync rows —
  // is silently clipped by the graph container's clipToBounds().
  val maxHeight = with(density) { maxHeightPx.coerceAtLeast(0f).toDp() }
  Card(
    modifier = modifier
      .width(PEER_DETAIL_CARD_WIDTH)
      .heightIn(max = maxHeight),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    ),
    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
  ) {
    // Keyed on the peer: the card swaps in place (same composition slot) when the
    // user taps a different peer, so an unkeyed scroll state would open B's card at
    // A's offset — past its own title, or clamped mid-card.
    val scrollState = remember(node.peerId) { ScrollState(0) }
    Column(
      modifier = Modifier
        .verticalScroll(scrollState)
        .padding(12.dp),
    ) {
      // No close button: tapping the card dismisses it, which is the gesture
      // people reach for anyway and leaves the card free of chrome.
      val detailsDescription = stringResource(R.string.presence_peer_details_description, node.displayName)
      Text(
        text = node.displayName,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        // Described here rather than on the Card. A contentDescription on the
        // card node suppresses the text payload of everything inside it.
        modifier = Modifier.semantics {
          contentDescription = detailsDescription
        },
      )

      if (detail == null) {
        // Only the synthetic cloud node reaches this — it has no DittoPeer
        // behind it. The local peer gets a real record built from LocalPeerInfo.
        Spacer(Modifier.height(6.dp))
        Text(
          text = stringResource(R.string.presence_detail_synthetic),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return@Column
      }

      Spacer(Modifier.height(8.dp))
      DetailRow(
        stringResource(R.string.presence_detail_peer_key),
        detail.peerKey.ifBlank { null },
        monospace = true,
        missing = stringResource(R.string.presence_not_reported),
      )
      DetailRow(
        stringResource(R.string.presence_detail_os),
        detail.os.takeIf { it != PeerOS.Unknown }?.displayName,
        missing = stringResource(R.string.presence_not_yet_known),
      )
      DetailRow(
        stringResource(R.string.presence_detail_sdk),
        detail.dittoSdkVersion,
        missing = stringResource(R.string.presence_not_yet_known),
      )
      DetailRow(
        stringResource(R.string.presence_detail_cloud_link),
        detail.isConnectedToDittoServer?.let {
          if (it) stringResource(R.string.status_connected) else stringResource(R.string.presence_cloud_none)
        },
      )
      DetailRow(
        stringResource(R.string.presence_detail_compatible),
        detail.isCompatible?.let {
          if (it) stringResource(R.string.presence_yes) else stringResource(R.string.presence_no)
        },
        missing = stringResource(R.string.presence_not_yet_known),
      )

      Spacer(Modifier.height(8.dp))
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      Spacer(Modifier.height(8.dp))

      DetailRow(
        stringResource(R.string.presence_detail_peer_metadata),
        metadataSummary(detail.peerMetadataKeyCount, detail.peerMetadata),
      )
      DetailRow(
        stringResource(R.string.presence_detail_identity_metadata),
        metadataSummary(detail.identityServiceMetadataKeyCount, detail.identityServiceMetadata),
      )

      Spacer(Modifier.height(8.dp))
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      Spacer(Modifier.height(8.dp))

      // Sync rows. system:data_sync_info is a local table computed from where this
      // device actually receives data, so it has no row at all for a peer we have
      // no session with. Say that, rather than showing a blank that reads as a bug.
      when {
        // This device. There is no data_sync_info row for ourselves — that table
        // records what REMOTE peers have confirmed of our commits — so saying
        // "not directly connected" here would be nonsense.
        node.isLocal -> {
          Text(
            text = stringResource(R.string.presence_this_device),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(Modifier.height(2.dp))
          Text(
            text = stringResource(R.string.presence_this_device_explanation),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        detail.isDirectlyConnected -> {
          DetailRow(
            stringResource(R.string.presence_synced_to_commit),
            detail.syncedUpToLocalCommitId?.toString(),
            missing = stringResource(R.string.presence_sync_nothing_yet),
          )
          DetailRow(
            stringResource(R.string.presence_last_update),
            formatLastUpdate(detail.lastUpdateReceivedTime),
            missing = stringResource(R.string.presence_sync_never),
          )
        }
        else -> {
          Text(
            text = stringResource(R.string.presence_no_sync_session),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(Modifier.height(2.dp))
          Text(
            text = stringResource(R.string.presence_no_sync_session_explanation),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      if (whiteboard != null) {
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(8.dp))
        WhiteboardLinkSection(whiteboard)
      }

      // Refocusing used to be a bare tap on an orbit peer. Tap now opens this
      // card, so the traversal lives here, labelled rather than hidden.
      if (onFocusPeer != null) {
        Spacer(Modifier.height(4.dp))
        val focusDescription = stringResource(R.string.presence_focus_this_peer)
        TextButton(
          onClick = onFocusPeer,
          modifier = Modifier.semantics { contentDescription = focusDescription },
        ) {
          Text(stringResource(R.string.presence_focus_this_peer), style = MaterialTheme.typography.labelMedium)
        }
      }
    }
  }
}

/**
 * The whiteboard-specific lower section: which Data Streams topics are live to this
 * peer, where snapshot hydration stands, and the observed traffic rate. Replaces the
 * old Troubleshooting screen's per-peer panel for peers that run the whiteboard app.
 */
@Composable
private fun WhiteboardLinkSection(peer: PeerDiagnostics) {
  Text(
    text = stringResource(R.string.presence_whiteboard_link),
    style = MaterialTheme.typography.labelMedium,
    fontWeight = FontWeight.Bold,
    color = MaterialTheme.colorScheme.onSurface,
  )
  Spacer(Modifier.height(4.dp))
  DetailRow(
    stringResource(R.string.presence_wb_transports),
    if (peer.transports.isEmpty()) {
      stringResource(R.string.no_direct_transport)
    } else {
      peer.transports.sorted().joinToString()
    },
  )
  DetailRow(
    "wb_live",
    stringResource(if (peer.liveConnected) R.string.status_connected else R.string.status_offline),
  )
  DetailRow(
    "wb_state",
    stringResource(if (peer.stateConnected) R.string.status_connected else R.string.status_offline),
  )
  DetailRow(
    stringResource(R.string.status_snapshot),
    stringResource(
      R.string.status_snapshot_value,
      stringResource(
        when (peer.snapshotStatus) {
          SnapshotStatus.Idle -> R.string.snapshot_idle
          SnapshotStatus.Queued -> R.string.snapshot_queued
          SnapshotStatus.Receiving -> R.string.snapshot_receiving
          SnapshotStatus.Merged -> R.string.snapshot_merged
          SnapshotStatus.Rejected -> R.string.snapshot_rejected
          SnapshotStatus.Acknowledged -> R.string.snapshot_acknowledged
          SnapshotStatus.Sending -> R.string.snapshot_sending
        },
      ),
      (peer.snapshotProgress * 100).toInt(),
    ),
  )
  DetailRow(
    stringResource(R.string.status_traffic),
    stringResource(
      R.string.status_traffic_value,
      peer.transmitMessagesPerSecond,
      peer.receiveMessagesPerSecond,
    ),
  )
  peer.lastError?.let {
    Spacer(Modifier.height(4.dp))
    Text(
      text = stringResource(R.string.last_error),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.error,
    )
    Text(
      text = it,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.error,
    )
  }
}

/** Label + value row. [value] of null renders [missing] in a muted style. */
@Composable
private fun DetailRow(
  label: String,
  value: String?,
  monospace: Boolean = false,
  missing: String = "—",
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 2.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.Top,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.width(96.dp),
    )
    Text(
      text = value ?: missing,
      style = MaterialTheme.typography.labelSmall,
      fontFamily = if (monospace) FontFamily.Monospace else null,
      // onSurfaceVariant, not `outline`: outline is a divider colour and lands at
      // ~2.1:1 against this card's surface in both themes, well under the 4.5:1 AA
      // floor for 11sp text — which would make the "why this is empty" copy
      // effectively invisible, i.e. the blank it was written to prevent.
      color = if (value == null) {
        MaterialTheme.colorScheme.onSurfaceVariant
      } else {
        MaterialTheme.colorScheme.onSurface
      },
      maxLines = if (monospace) 2 else 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
  }
}

/**
 * Metadata is shown as a key count, never as the raw document. The SDK caps each
 * metadata object at 4 KB, which no card can hold, and rendering it inline would make
 * the card's height depend on the peer.
 */
@Composable
private fun metadataSummary(keyCount: Int, raw: String?): String? = when {
  keyCount > 0 -> pluralStringResource(R.plurals.presence_metadata_keys, keyCount, keyCount)
  // An empty SDK object stringifies to "{}" — non-blank but carrying nothing. Treating
  // that as "present" labelled every peer that never set metadata as having some.
  !raw.isNullOrBlank() && raw.trim() != "{}" -> stringResource(R.string.presence_metadata_present)
  else -> null
}

@Composable
private fun formatLastUpdate(epochMillis: Double?): String? {
  val ms = epochMillis?.toLong() ?: return null
  if (ms <= 0L) return null
  // LocalConfiguration is observable composition state, so a locale change
  // reformats rather than leaving a stale timestamp until the next recomposition.
  val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
  val diff = System.currentTimeMillis() - ms
  val minutes = (diff / 60_000L).toInt()
  val hours = (diff / 3_600_000L).toInt()
  return when {
    diff < 0L -> SimpleDateFormat("MMM d, h:mm a", locale).format(Date(ms))
    diff < 60_000L -> stringResource(R.string.presence_just_now)
    diff < 3_600_000L -> pluralStringResource(R.plurals.presence_minutes_ago, minutes, minutes)
    diff < 86_400_000L -> pluralStringResource(R.plurals.presence_hours_ago, hours, hours)
    else -> SimpleDateFormat("MMM d, h:mm a", locale).format(Date(ms))
  }
}
