package com.ditto.whiteboard.ui.peers

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ditto.whiteboard.R
import com.ditto.whiteboard.data.ProfileSettings
import com.ditto.whiteboard.transport.PeerDiagnostics
import com.ditto.whiteboard.transport.SnapshotStatus
import com.ditto.whiteboard.transport.TransportDiagnostics

private val PeerCardHeight = 410.dp

/**
 * Direct-peer diagnostic cards. This deliberately presents the same nearby
 * peers as Transport, but makes the connection and stream state quick to scan.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerListScreen(
  diagnostics: TransportDiagnostics,
  profile: ProfileSettings,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val peers = diagnostics.peers.values.sortedBy { it.peerKey }

  Scaffold(
    modifier = modifier.fillMaxSize(),
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.peer_list_title)) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(
              Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = stringResource(R.string.action_back),
            )
          }
        },
      )
    },
  ) { padding ->
    LazyVerticalGrid(
      columns = GridCells.Adaptive(minSize = 280.dp),
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(
        start = 16.dp,
        top = padding.calculateTopPadding() + 16.dp,
        end = 16.dp,
        bottom = padding.calculateBottomPadding() + 16.dp,
      ),
      horizontalArrangement = Arrangement.spacedBy(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      item(key = "local") {
        LocalPeerCard(
          peerKey = diagnostics.localPeerKey,
          profileName = profile.displayName,
        )
      }
      items(peers, key = { it.peerKey }) { peer ->
        RemotePeerCard(peer)
      }
      if (peers.isEmpty()) {
        item(key = "empty") { EmptyPeersCard() }
      }
    }
  }
}

@Composable
private fun LocalPeerCard(peerKey: String, profileName: String) {
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    shape = MaterialTheme.shapes.large,
    tonalElevation = 2.dp,
    modifier = Modifier.fillMaxWidth().height(PeerCardHeight),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      PeerCardHeader(
        iconTint = MaterialTheme.colorScheme.primary,
        title = stringResource(R.string.peer_list_this_device),
        subtitle = profileName,
        online = true,
      )
      HorizontalDivider()
      PeerValueRow(stringResource(R.string.peer_list_peer_key), peerKey, monospaced = true)
      PeerValueRow(stringResource(R.string.peer_list_platform), "Android ${Build.VERSION.RELEASE}")
      PeerValueRow(stringResource(R.string.peer_list_whiteboard), "Kotlin")
    }
  }
}

@Composable
private fun RemotePeerCard(peer: PeerDiagnostics) {
  val appearance = peerCardAppearance(peer.transports)
  val title = peer.displayName ?: stringResource(R.string.peer_list_nearby_peer, peer.peerKey.takeLast(8))
  val connected = peer.liveConnected || peer.stateConnected
  val accessibilityLabel = stringResource(R.string.peer_list_card_description, title)

  Surface(
    color = Color.Transparent,
    shape = MaterialTheme.shapes.large,
    shadowElevation = 4.dp,
    modifier = Modifier
      .fillMaxWidth()
      .height(PeerCardHeight)
      .semantics { contentDescription = accessibilityLabel },
  ) {
    Column(
      modifier = Modifier
        .background(Brush.linearGradient(appearance.colors))
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      PeerCardHeader(
        iconTint = appearance.foreground,
        title = title,
        subtitle = peer.transports.sorted().joinToString().ifEmpty {
          stringResource(R.string.no_direct_transport)
        },
        online = connected,
        foreground = appearance.foreground,
      )
      HorizontalDivider(color = appearance.foreground.copy(alpha = 0.3f))
      PeerValueRow(
        stringResource(R.string.peer_list_peer_key),
        peer.peerKey,
        foreground = appearance.foreground,
        monospaced = true,
      )
      PeerValueRow(
        "wb_live",
        stringResource(if (peer.liveConnected) R.string.status_connected else R.string.status_offline),
        foreground = appearance.foreground,
      )
      PeerValueRow(
        "wb_state",
        stringResource(if (peer.stateConnected) R.string.status_connected else R.string.status_offline),
        foreground = appearance.foreground,
      )
      PeerValueRow(
        stringResource(R.string.status_snapshot),
        stringResource(
          R.string.status_snapshot_value,
          stringResource(peer.snapshotStatus.labelRes()),
          (peer.snapshotProgress * 100).toInt(),
        ),
        foreground = appearance.foreground,
      )
      PeerValueRow(
        stringResource(R.string.status_traffic),
        stringResource(
          R.string.status_traffic_value,
          peer.transmitMessagesPerSecond,
          peer.receiveMessagesPerSecond,
        ),
        foreground = appearance.foreground,
      )
      peer.lastError?.let { error ->
        Text(
          text = error,
          style = MaterialTheme.typography.labelMedium,
          color = if (appearance.foreground == Color.Black) Color(0xFF8B0000) else Color(0xFFFFDAD6),
          maxLines = 3,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Composable
private fun EmptyPeersCard() {
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    shape = MaterialTheme.shapes.large,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Icon(Icons.Default.Groups, contentDescription = null, modifier = Modifier.size(32.dp))
      Text(stringResource(R.string.peer_list_no_remote_peers), style = MaterialTheme.typography.titleMedium)
      Text(
        stringResource(R.string.peer_list_no_remote_peers_description),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun PeerCardHeader(
  iconTint: Color,
  title: String,
  subtitle: String,
  online: Boolean,
  foreground: Color = MaterialTheme.colorScheme.onSurface,
) {
  Row(verticalAlignment = Alignment.Top) {
    Icon(
      Icons.Default.Groups,
      contentDescription = null,
      tint = iconTint,
      modifier = Modifier.size(28.dp),
    )
    Spacer(Modifier.width(10.dp))
    Column(Modifier.weight(1f)) {
      Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = foreground,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = foreground.copy(alpha = 0.8f),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Spacer(Modifier.width(8.dp))
    Box(
      modifier = Modifier
        .size(10.dp)
        .background(
          if (online) Color(0xFF32D583) else foreground.copy(alpha = 0.55f),
          shape = androidx.compose.foundation.shape.CircleShape,
        ),
    )
  }
}

@Composable
private fun PeerValueRow(
  label: String,
  value: String,
  foreground: Color = MaterialTheme.colorScheme.onSurface,
  monospaced: Boolean = false,
) {
  Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
    Text(
      label,
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.SemiBold,
      color = foreground.copy(alpha = 0.8f),
    )
    Text(
      value,
      style = MaterialTheme.typography.bodyMedium,
      fontFamily = if (monospaced) FontFamily.Monospace else FontFamily.Default,
      color = foreground,
      maxLines = if (monospaced) 2 else 3,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

private data class PeerCardAppearance(val colors: List<Color>, val foreground: Color)

private fun peerCardAppearance(transports: Set<String>): PeerCardAppearance {
  val names = transports.map { it.lowercase() }
  return when {
    names.any { it.contains("cloud") } -> PeerCardAppearance(
      colors = listOf(Color(0xFF5933A8), Color(0xFF331F75)),
      foreground = Color.White,
    )
    names.any { it.contains("websocket") || it.contains("web socket") } -> PeerCardAppearance(
      colors = listOf(Color(0xFFD97A00), Color(0xFF994D00)),
      foreground = Color.White,
    )
    names.any { it.contains("lan") || it.contains("access") } -> PeerCardAppearance(
      colors = listOf(Color(0xFF0D8540), Color(0xFF055224)),
      foreground = Color.White,
    )
    names.any { it.contains("p2p") || it.contains("wifi") } -> PeerCardAppearance(
      colors = listOf(Color(0xFFC71A38), Color(0xFF800A1F)),
      foreground = Color.White,
    )
    names.any { it.contains("multicast") } -> PeerCardAppearance(
      colors = listOf(Color(0xFFFFD60A), Color(0xFFAA7D00)),
      foreground = Color.Black,
    )
    names.any { it.contains("bluetooth") } -> PeerCardAppearance(
      colors = listOf(Color(0xFF0066D9), Color(0xFF003399)),
      foreground = Color.White,
    )
    else -> PeerCardAppearance(
      colors = listOf(Color(0xFF63636A), Color(0xFF3A3A3C)),
      foreground = Color.White,
    )
  }
}

private fun SnapshotStatus.labelRes(): Int = when (this) {
  SnapshotStatus.Idle -> R.string.snapshot_idle
  SnapshotStatus.Queued -> R.string.snapshot_queued
  SnapshotStatus.Receiving -> R.string.snapshot_receiving
  SnapshotStatus.Merged -> R.string.snapshot_merged
  SnapshotStatus.Rejected -> R.string.snapshot_rejected
  SnapshotStatus.Acknowledged -> R.string.snapshot_acknowledged
  SnapshotStatus.Sending -> R.string.snapshot_sending
}
