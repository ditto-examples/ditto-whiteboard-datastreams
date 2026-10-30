package com.ditto.whiteboard.ui.troubleshooting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ditto.whiteboard.R
import com.ditto.whiteboard.transport.PeerPing
import com.ditto.whiteboard.transport.PeerPingException
import com.ditto.whiteboard.transport.PeerPingFailure
import com.ditto.whiteboard.transport.TransportDiagnostics
import com.ditto.whiteboard.transport.TransportMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun PeerPingCard(diagnostics: TransportDiagnostics, onPing: suspend (String, (Double) -> Unit) -> Unit) {
  val scope = rememberCoroutineScope()
  var selected by remember { mutableStateOf("") }
  var expanded by remember { mutableStateOf(false) }
  var job by remember { mutableStateOf<Job?>(null) }
  var samples by remember { mutableStateOf(emptyList<Double>()) }
  var outcome by remember { mutableStateOf<Int?>(null) }
  var testedPeer by remember { mutableStateOf<String?>(null) }
  val available = diagnostics.running && diagnostics.mode == TransportMode.NearbyMesh
  val peers = diagnostics.peers.values.sortedBy { it.peerKey }

  Card(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
    Column(Modifier.padding(16.dp)) {
      Text(stringResource(R.string.ping_title), style = MaterialTheme.typography.titleMedium)
      Text(stringResource(R.string.ping_description), style = MaterialTheme.typography.bodySmall)
      if (available) {
        TextButton(onClick = { expanded = true }, enabled = job == null) {
          Text(diagnostics.peers[selected]?.let { "${it.displayName ?: it.peerKey.takeLast(8)} · ${it.peerKey.takeLast(8)}" }
            ?: stringResource(R.string.ping_select_peer))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
          peers.forEach { peer ->
            DropdownMenuItem(
              text = { Text("${peer.displayName ?: peer.peerKey.takeLast(8)} · ${peer.peerKey.takeLast(8)}") },
              onClick = { selected = peer.peerKey; expanded = false },
            )
          }
        }
        if (peers.isEmpty()) Text(stringResource(R.string.ping_no_peers))
      } else {
        Text(stringResource(R.string.ping_unavailable))
      }
      if (job != null) {
        Text(stringResource(R.string.ping_waiting))
        TextButton(onClick = { job?.cancel() }) { Text(stringResource(R.string.ping_cancel)) }
      } else {
        Button(
          enabled = available && selected in diagnostics.peers,
          modifier = Modifier.testTag("runPeerPingButton"),
          onClick = {
            val peer = selected
            samples = emptyList()
            outcome = null
            testedPeer = peer
            val task = scope.launch(start = CoroutineStart.LAZY) {
              try {
                onPing(peer) { samples = samples + it }
                outcome = R.string.ping_complete
              } catch (cancelled: CancellationException) {
                outcome = R.string.ping_cancelled
                throw cancelled
              } catch (error: Exception) {
                outcome = when ((error as? PeerPingException)?.failure) {
                  PeerPingFailure.Unavailable -> R.string.ping_unavailable
                  PeerPingFailure.Busy -> R.string.ping_busy
                  PeerPingFailure.ConnectionFailed -> R.string.ping_connection_failed
                  PeerPingFailure.TimedOut -> R.string.ping_timeout
                  PeerPingFailure.Closed -> R.string.ping_closed
                  PeerPingFailure.UnexpectedEcho -> R.string.ping_mismatch
                  PeerPingFailure.TooLarge -> R.string.ping_too_large
                  null -> R.string.ping_failed
                }
              } finally { job = null }
            }
            job = task
            task.start()
          },
        ) { Text(stringResource(R.string.ping_run)) }
      }
      testedPeer?.let { Text(stringResource(R.string.ping_results_peer, it.takeLast(8))) }
      samples.forEachIndexed { index, rtt -> Text(stringResource(R.string.ping_sample, index + 1, rtt)) }
      if (samples.isNotEmpty()) Text(stringResource(R.string.ping_verified, samples.size, PeerPing.COUNT))
      outcome?.let { Text(stringResource(it)) }
      Text(stringResource(R.string.ping_scope), style = MaterialTheme.typography.bodySmall)
    }
  }
}
