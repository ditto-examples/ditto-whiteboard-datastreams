package com.ditto.whiteboard.ui.troubleshooting

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ditto.whiteboard.transport.PeerDiagnostics
import com.ditto.whiteboard.transport.SnapshotStatus
import com.ditto.whiteboard.transport.TransportDiagnostics
import com.ditto.whiteboard.transport.TransportMode
import com.ditto.whiteboard.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TroubleshootingScreen(
  diagnostics: TransportDiagnostics,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var directOnly by remember { mutableStateOf(true) }
  var selectedPeerKey by remember { mutableStateOf<String?>(null) }
  val selectedPeer = selectedPeerKey?.let(diagnostics.peers::get)

  Scaffold(
    modifier = modifier.fillMaxSize(),
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.troubleshooting_title)) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
          }
        },
      )
    },
  ) { padding ->
    BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
      val wide = maxWidth >= 700.dp
      Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text(
              stringResource(
                when (diagnostics.mode) {
                  TransportMode.LocalPreview -> R.string.local_preview
                  TransportMode.NearbyMesh -> R.string.transport_mode_nearby_mesh
                },
              ),
              style = MaterialTheme.typography.titleMedium,
            )
            Text(
              stringResource(if (diagnostics.running) R.string.session_running else R.string.session_stopped),
              style = MaterialTheme.typography.bodySmall,
            )
          }
          SingleChoiceSegmentedButtonRow {
            listOf(
              stringResource(R.string.graph_direct),
              stringResource(R.string.graph_full),
            ).forEachIndexed { index, label ->
              SegmentedButton(
                selected = directOnly == (index == 0),
                onClick = { directOnly = index == 0 },
                shape = SegmentedButtonDefaults.itemShape(index, 2),
              ) { Text(label) }
            }
          }
        }
        diagnostics.connectivityMessage?.let {
          Text(it, Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
        }
        if (wide) {
          Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            GraphPanel(diagnostics, directOnly, selectedPeerKey, { selectedPeerKey = it }, Modifier.weight(1f))
            Column(Modifier.width(320.dp).fillMaxHeight().padding(top = 12.dp)) {
              if (selectedPeer == null) {
                Text(stringResource(R.string.select_peer_instruction))
              } else {
                PeerDetails(selectedPeer)
              }
            }
          }
        } else {
          GraphPanel(diagnostics, directOnly, selectedPeerKey, { selectedPeerKey = it }, Modifier.weight(1f))
        }
      }
      if (!wide && selectedPeer != null) {
        ModalBottomSheet(onDismissRequest = { selectedPeerKey = null }) {
          PeerDetails(selectedPeer, Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
          Spacer(Modifier.height(24.dp))
        }
      }
    }
  }
}

@Composable
private fun GraphPanel(
  diagnostics: TransportDiagnostics,
  directOnly: Boolean,
  selectedPeer: String?,
  onSelectPeer: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier.padding(top = 12.dp)) {
    PresenceGraph(
      diagnostics = diagnostics,
      directOnly = directOnly,
      selectedPeer = selectedPeer,
      onSelectPeer = onSelectPeer,
      modifier = Modifier.fillMaxWidth().weight(1f).clip(MaterialTheme.shapes.large),
    )
    TransportLegend(Modifier.padding(vertical = 12.dp))
    if (diagnostics.incompatiblePeers.isNotEmpty()) {
      Text(stringResource(R.string.incompatible_protocol_versions), style = MaterialTheme.typography.titleSmall)
      diagnostics.incompatiblePeers.forEach { (peer, version) ->
        Text(
          stringResource(R.string.peer_protocol, peer.takeLast(8), version),
          color = MaterialTheme.colorScheme.error,
        )
      }
    }
  }
}

@Composable
private fun TransportLegend(modifier: Modifier = Modifier) {
  FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    listOf(
      stringResource(R.string.transport_bluetooth_le),
      stringResource(R.string.transport_wifi_aware),
      stringResource(R.string.transport_lan),
      stringResource(R.string.transport_cloud),
    ).forEach { name ->
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Spacer(Modifier.size(12.dp).background(transportColor(name), MaterialTheme.shapes.extraSmall))
        Text(name, style = MaterialTheme.typography.labelSmall)
      }
    }
  }
}

@Composable
private fun PeerDetails(peer: PeerDiagnostics, modifier: Modifier = Modifier) {
  val transports = if (peer.transports.isEmpty()) {
    setOf(stringResource(R.string.no_direct_transport))
  } else {
    peer.transports
  }
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Text(peer.displayName ?: peer.peerKey.takeLast(10), style = MaterialTheme.typography.titleLarge)
    Text(peer.peerKey, style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      transports.forEach { transport ->
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
          Text(transport, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
        }
      }
    }
    HorizontalDivider()
    StatusRow(
      "wb_live",
      stringResource(if (peer.liveConnected) R.string.status_connected else R.string.status_offline),
    )
    StatusRow(
      "wb_state",
      stringResource(if (peer.stateConnected) R.string.status_connected else R.string.status_offline),
    )
    StatusRow(
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
    StatusRow(
      stringResource(R.string.status_traffic),
      stringResource(
        R.string.status_traffic_value,
        peer.transmitMessagesPerSecond,
        peer.receiveMessagesPerSecond,
      ),
    )
    peer.lastError?.let {
      HorizontalDivider()
      Text(
        stringResource(R.string.last_error),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.error,
      )
      Text(it, color = MaterialTheme.colorScheme.error)
    }
  }
}

@Composable
private fun StatusRow(label: String, value: String) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    Text(value, style = MaterialTheme.typography.bodyMedium)
  }
}
