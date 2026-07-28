package com.ditto.whiteboard.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ditto.whiteboard.ui.BoardUiState
import com.ditto.whiteboard.ui.WHITEBOARD_COLORS

internal data class ConnectedPerson(
  val peerKey: String,
  val displayName: String,
  val colorArgb: Int?,
  val isLocal: Boolean,
)

internal fun BoardUiState.connectedPeople(): List<ConnectedPerson> {
  val localPeerKey = diagnostics.localPeerKey
  val localProfile = board.profiles[localPeerKey]
  val local = ConnectedPerson(
    peerKey = localPeerKey,
    displayName = localProfile?.displayName ?: "You",
    colorArgb = localProfile?.colorArgb ?: colorArgb,
    isLocal = true,
  )
  val remote = diagnostics.peers.values
    .asSequence()
    .filterNot { it.peerKey in diagnostics.incompatiblePeers }
    .map { peer ->
      val profile = board.profiles[peer.peerKey]
      ConnectedPerson(
        peerKey = peer.peerKey,
        displayName = profile?.displayName ?: peer.displayName ?: "Nearby artist ${peer.peerKey.takeLast(4)}",
        colorArgb = profile?.colorArgb ?: peer.colorArgb,
        isLocal = false,
      )
    }
    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, ConnectedPerson::displayName).thenBy(ConnectedPerson::peerKey))
    .toList()
  return listOf(local) + remote
}

@Composable
internal fun ConnectedPeoplePane(
  state: BoardUiState,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
  expanded: Boolean = false,
) {
  val people = remember(state.board.profiles, state.diagnostics, state.colorArgb) { state.connectedPeople() }
  Surface(
    modifier = modifier
      .then(if (expanded) Modifier.width(320.dp).fillMaxHeight() else Modifier.fillMaxWidth().heightIn(min = 260.dp, max = 560.dp)),
    color = MaterialTheme.colorScheme.surfaceContainer,
    tonalElevation = if (expanded) 2.dp else 0.dp,
  ) {
    Column {
      Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(Modifier.weight(1f)) {
          Text("People on this board", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
          Text(
            text = "${people.size} connected",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close people list") }
      }
      HorizontalDivider()
      LazyColumn(
        modifier = Modifier.weight(1f),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
      ) {
        items(people, key = ConnectedPerson::peerKey) { person ->
          ConnectedPersonRow(person)
        }
        if (people.size == 1) {
          item {
            Text(
              text = "No one else is connected yet.",
              modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ConnectedPersonRow(person: ConnectedPerson) {
  val colorName = person.colorArgb?.let(::assignedColorName)
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Box(
      modifier = Modifier
        .size(42.dp)
        .background(person.colorArgb?.let(::Color) ?: MaterialTheme.colorScheme.surfaceVariant, CircleShape)
        .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
        .semantics {
          contentDescription = colorName?.let { "$it assigned color" } ?: "Assigned color syncing"
        },
      contentAlignment = Alignment.Center,
    ) {
      if (person.colorArgb == null) {
        Icon(
          Icons.Default.MoreHoriz,
          contentDescription = null,
          modifier = Modifier.size(22.dp),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    Column(Modifier.weight(1f)) {
      Text(
        text = person.displayName,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      val detail = when {
        person.isLocal -> "You • ${colorName ?: "Color syncing"}"
        colorName == null -> "Color syncing…"
        else -> colorName
      }
      Text(
        text = detail,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    if (person.isLocal) {
      Spacer(Modifier.width(4.dp))
      Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer) {
        Text("You", Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
      }
    }
  }
}

private fun assignedColorName(colorArgb: Int): String = when (colorArgb) {
  WHITEBOARD_COLORS[0] -> "Charcoal"
  WHITEBOARD_COLORS[1] -> "Blue"
  WHITEBOARD_COLORS[2] -> "Green"
  WHITEBOARD_COLORS[3] -> "Red"
  WHITEBOARD_COLORS[4] -> "Purple"
  WHITEBOARD_COLORS[5] -> "Orange"
  WHITEBOARD_COLORS[6] -> "Teal"
  WHITEBOARD_COLORS[7] -> "White"
  else -> "Custom color"
}
