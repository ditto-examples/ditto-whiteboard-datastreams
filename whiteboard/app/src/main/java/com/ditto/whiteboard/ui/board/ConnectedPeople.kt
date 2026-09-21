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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ditto.whiteboard.ui.BoardUiState
import com.ditto.whiteboard.R
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
    displayName = localProfile?.displayName.orEmpty(),
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
        displayName = profile?.displayName ?: peer.displayName.orEmpty(),
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
  framed: Boolean = true,
) {
  val people = remember(state.board.profiles, state.diagnostics, state.colorArgb) { state.connectedPeople() }
  if (framed) {
    Surface(
      modifier = modifier
        .then(if (expanded) Modifier.width(320.dp).fillMaxHeight() else Modifier.fillMaxWidth().heightIn(min = 260.dp, max = 560.dp)),
      color = MaterialTheme.colorScheme.surfaceContainer,
      tonalElevation = if (expanded) 2.dp else 0.dp,
    ) {
      ConnectedPeopleContent(people, onClose)
    }
  } else {
    Box(modifier.fillMaxWidth()) {
      ConnectedPeopleContent(people, onClose)
    }
  }
}

@Composable
private fun ConnectedPeopleContent(people: List<ConnectedPerson>, onClose: () -> Unit) {
    Column {
      Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(Modifier.weight(1f)) {
          Text(
            stringResource(R.string.people_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
          )
          Text(
            text = pluralStringResource(R.plurals.people_connected, people.size, people.size),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        IconButton(onClick = onClose) {
          Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close_people))
        }
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
              text = stringResource(R.string.no_people_connected),
              modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
}

@Composable
private fun ConnectedPersonRow(person: ConnectedPerson) {
  val colorName = person.colorArgb?.let { assignedColorName(it) }
  val displayName = if (person.displayName.isBlank()) {
    if (person.isLocal) {
      stringResource(R.string.you)
    } else {
      stringResource(R.string.nearby_artist, person.peerKey.takeLast(4))
    }
  } else person.displayName
  val syncing = stringResource(R.string.color_syncing)
  val assignedColorDescription = if (colorName != null) {
    stringResource(R.string.assigned_color, colorName)
  } else {
    stringResource(R.string.assigned_color_syncing)
  }
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
          contentDescription = assignedColorDescription
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
        text = displayName,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      val detail = when {
        person.isLocal -> stringResource(R.string.you_with_color, colorName ?: syncing)
        colorName == null -> syncing
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
        Text(
          stringResource(R.string.you),
          Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
          style = MaterialTheme.typography.labelMedium,
        )
      }
    }
  }
}

@Composable
private fun assignedColorName(colorArgb: Int): String = stringResource(
  when (colorArgb) {
    WHITEBOARD_COLORS[0] -> R.string.color_charcoal
    WHITEBOARD_COLORS[1] -> R.string.color_blue
    WHITEBOARD_COLORS[2] -> R.string.color_green
    WHITEBOARD_COLORS[3] -> R.string.color_red
    WHITEBOARD_COLORS[4] -> R.string.color_purple
    WHITEBOARD_COLORS[5] -> R.string.color_orange
    WHITEBOARD_COLORS[6] -> R.string.color_teal
    WHITEBOARD_COLORS[7] -> R.string.color_magenta
    else -> R.string.color_custom
  },
)
