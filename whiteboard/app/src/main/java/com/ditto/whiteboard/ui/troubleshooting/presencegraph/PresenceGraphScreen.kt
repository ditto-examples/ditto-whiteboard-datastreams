package com.ditto.whiteboard.ui.troubleshooting.presencegraph

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ditto.whiteboard.R
import com.ditto.whiteboard.transport.TransportDiagnostics

/**
 * Full-screen presence graph viewer: the full mesh with focus mode, peer search, a
 * detail card per peer (including the whiteboard stream section for peers running
 * this app), the connection legend, and the Direct/zoom/reset/eye controls.
 *
 * Viewer state (mode, query, focus, controls visibility) is hoisted here at the route
 * level with `rememberSaveable`, so it survives configuration changes without living
 * in the view model.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresenceGraphScreen(
  state: PresenceGraphUiState,
  diagnostics: TransportDiagnostics,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var directOnly by rememberSaveable { mutableStateOf(false) }
  var query by rememberSaveable { mutableStateOf("") }
  var controlsVisible by rememberSaveable { mutableStateOf(true) }
  var focusedPeerId by rememberSaveable { mutableStateOf<String?>(null) }
  var pendingFocusPeerId by rememberSaveable { mutableStateOf<String?>(null) }

  val candidates = PresencePeerSearch.candidates(state)
  val matches = PresencePeerSearch.matches(candidates, query)

  fun pick(peerKey: String) {
    // Focus only exists in the full mesh; the view consumes pendingFocusPeerId once
    // the rebuilt projection has placed the peer.
    if (directOnly) directOnly = false
    pendingFocusPeerId = peerKey
    query = ""
  }

  Scaffold(
    modifier = modifier.fillMaxSize(),
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.presence_graph_title)) },
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
    Column(Modifier.padding(padding).fillMaxSize()) {
      Box(Modifier.fillMaxWidth().weight(1f)) {
        PresenceGraphView(
          peersUiState = state,
          showDirectConnectedOnly = directOnly,
          onToggleDirectConnectedOnly = { directOnly = !directOnly },
          focusedPeerId = focusedPeerId,
          onFocusedPeerChange = { focusedPeerId = it },
          controlsVisible = controlsVisible,
          onToggleControlsVisible = { controlsVisible = !controlsVisible },
          whiteboardDiagnostics = diagnostics.peers,
          searchMatchIds = PresencePeerSearch.matchIds(candidates, query),
          pendingFocusPeerId = pendingFocusPeerId,
          onPendingFocusConsumed = { pendingFocusPeerId = null },
          modifier = Modifier.fillMaxSize(),
        )
        // Search rides as a floating field over the canvas so the graph gets
        // every pixel; results card hangs below it.
        PresencePeerSearchBar(
          query = query,
          onQueryChange = { query = it },
          onSubmit = { matches.firstOrNull { !it.isLocal }?.let { pick(it.key) } },
          modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth(0.72f)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (PresencePeerSearch.isActive(query)) {
          PresencePeerSearchResults(
            query = query,
            matches = matches,
            onPick = { pick(it) },
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp),
          )
        }
      }
    }
  }
}
