package com.ditto.whiteboard.ui.troubleshooting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ditto.whiteboard.R
import com.ditto.whiteboard.data.DebugTransportSettings
import com.ditto.whiteboard.transport.TransportDiagnostics
import com.ditto.whiteboard.transport.TransportMode
import com.ditto.whiteboard.ui.theme.dittoSwitchColors
import com.ditto.whiteboard.ui.troubleshooting.presencegraph.PresenceGraphUiState
import com.ditto.whiteboard.ui.troubleshooting.presencegraph.PresenceGraphView

/**
 * Troubleshooting keeps its identity — transport mode, session state, debug transport
 * toggles, and the incompatible-version list — but the peer map is the shared Edge
 * Studio presence viewer (full mesh, focus mode, detail cards). The segmented
 * Direct/Full toggle drives the viewer's `showDirectConnectedOnly`; tapping a peer in
 * Full mode focuses it, and tapping again opens its card, which carries the
 * whiteboard stream diagnostics (wb_live/wb_state, snapshot, traffic) for peers
 * running this app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TroubleshootingScreen(
  diagnostics: TransportDiagnostics,
  presenceGraphState: PresenceGraphUiState,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  embedded: Boolean = false,
) {
  var directOnly by rememberSaveable { mutableStateOf(true) }
  var focusedPeerId by rememberSaveable { mutableStateOf<String?>(null) }
  var controlsVisible by rememberSaveable { mutableStateOf(true) }

  Scaffold(
    modifier = modifier.fillMaxSize(),
    topBar = {
      if (!embedded) {
        TopAppBar(
          title = { Text(stringResource(R.string.troubleshooting_title)) },
          navigationIcon = {
            IconButton(onClick = onBack) {
              Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
          },
        )
      }
    },
  ) { padding ->
    BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
      val graphHeight = if (maxWidth >= 700.dp) 560.dp else 420.dp
      Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
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
        DebugTransportsSection(Modifier.padding(top = 8.dp))
        PresenceGraphView(
          peersUiState = presenceGraphState,
          showDirectConnectedOnly = directOnly,
          onToggleDirectConnectedOnly = { directOnly = !directOnly },
          focusedPeerId = focusedPeerId,
          onFocusedPeerChange = { focusedPeerId = it },
          controlsVisible = controlsVisible,
          onToggleControlsVisible = { controlsVisible = !controlsVisible },
          whiteboardDiagnostics = diagnostics.peers,
          showDirectToggle = false,
          modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .height(graphHeight)
            .clip(MaterialTheme.shapes.large),
        )
        if (diagnostics.incompatiblePeers.isNotEmpty()) {
          Text(
            stringResource(R.string.incompatible_protocol_versions),
            Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.titleSmall,
          )
          diagnostics.incompatiblePeers.forEach { (peer, version) ->
            Text(
              stringResource(R.string.peer_protocol, peer.takeLast(8), version),
              color = MaterialTheme.colorScheme.error,
            )
          }
        }
        Spacer(Modifier.height(24.dp))
      }
    }
  }
}

@Composable
private fun DebugTransportsSection(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val settings = remember { DebugTransportSettings(context) }
  var ble by remember { mutableStateOf(settings.bluetoothLeEnabled) }
  var wifiAware by remember { mutableStateOf(settings.wifiAwareEnabled) }
  var lan by remember { mutableStateOf(settings.lanEnabled) }
  var mdns by remember { mutableStateOf(settings.mdnsEnabled) }
  var multicast by remember { mutableStateOf(settings.multicastEnabled) }

  Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium) {
    Column(Modifier.padding(16.dp)) {
      Text("Debug transports", style = MaterialTheme.typography.titleSmall)
      Text(
        "Applied at startup — restart the app after changing.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(8.dp))
      DebugTransportRow("Bluetooth LE", ble) { ble = it; settings.bluetoothLeEnabled = it }
      DebugTransportRow("Wi-Fi Aware", wifiAware) { wifiAware = it; settings.wifiAwareEnabled = it }
      DebugTransportRow("LAN", lan) { lan = it; settings.lanEnabled = it }
      DebugTransportRow("mDNS", mdns, enabled = lan) { mdns = it; settings.mdnsEnabled = it }
      DebugTransportRow("LAN multicast", multicast, enabled = lan) { multicast = it; settings.multicastEnabled = it }
    }
  }
}

@Composable
private fun DebugTransportRow(
  label: String,
  checked: Boolean,
  enabled: Boolean = true,
  onChange: (Boolean) -> Unit,
) {
  Row(
    Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, style = MaterialTheme.typography.bodyMedium)
    Switch(
      checked = checked,
      onCheckedChange = onChange,
      enabled = enabled,
      colors = dittoSwitchColors(),
    )
  }
}
