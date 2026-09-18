package com.ditto.whiteboard.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Rectangle
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Troubleshoot
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import com.ditto.whiteboard.domain.BoardObject
import com.ditto.whiteboard.R
import com.ditto.whiteboard.domain.BoardState
import com.ditto.whiteboard.domain.DrawingTool
import com.ditto.whiteboard.domain.LogicalPoint
import com.ditto.whiteboard.domain.ObjectId
import com.ditto.whiteboard.domain.OperationId
import com.ditto.whiteboard.domain.OperationStamp
import com.ditto.whiteboard.transport.PeerDiagnostics
import com.ditto.whiteboard.transport.TransportDiagnostics
import com.ditto.whiteboard.ui.BoardUiState
import com.ditto.whiteboard.ui.WHITEBOARD_COLORS
import com.ditto.whiteboard.ui.theme.WhiteboardTheme
import kotlinx.collections.immutable.persistentMapOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardScreen(
  state: BoardUiState,
  onSelectTool: (DrawingTool) -> Unit,
  onSelectColor: (Int) -> Unit,
  onPreview: (String, List<LogicalPoint>) -> Unit,
  onCommit: (String, List<LogicalPoint>, String) -> Unit,
  onClear: () -> Unit,
  onEditProfile: () -> Unit,
  onTroubleshooting: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var confirmClear by rememberSaveable { mutableStateOf(false) }
  var textAnchor by rememberSaveable { mutableStateOf<Long?>(null) }
  var textGestureId by rememberSaveable { mutableStateOf<String?>(null) }
  var text by rememberSaveable { mutableStateOf("") }
  var showPeople by rememberSaveable { mutableStateOf(false) }
  val connectedCount = remember(state.board.profiles, state.diagnostics, state.colorArgb) {
    state.connectedPeople().size
  }

  BoxWithConstraints(modifier.fillMaxSize()) {
    // Account for Scaffold/top-system insets as well as six tools plus eight 48dp color targets.
    // Short tablets/foldables keep the compact overflow toolbar so every action remains reachable.
    val expanded = maxWidth >= 840.dp && maxHeight >= 900.dp
    Scaffold(
      modifier = Modifier.fillMaxSize(),
      topBar = {
        TopAppBar(
          title = { Text(stringResource(R.string.board_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
          actions = {
            IconButton(onClick = { showPeople = !showPeople }) {
              BadgedBox(badge = { Badge { Text(connectedCount.toString()) } }) {
                Icon(
                  Icons.Default.Groups,
                  stringResource(R.string.connected_people_description, connectedCount),
                )
              }
            }
            IconButton(onClick = onEditProfile) {
              Icon(Icons.Default.Person, stringResource(R.string.action_edit_profile))
            }
            IconButton(onClick = onTroubleshooting) {
              Icon(Icons.Default.Troubleshoot, stringResource(R.string.action_troubleshooting))
            }
            IconButton(
              onClick = { confirmClear = true },
              enabled = state.diagnostics.editingReady,
            ) { Icon(Icons.Default.DeleteSweep, stringResource(R.string.action_clear_board)) }
          },
        )
      },
      bottomBar = {
        if (!expanded) CompactToolbar(state.tool, state.colorArgb, onSelectTool, onSelectColor)
      },
    ) { padding ->
      Row(Modifier.padding(padding).fillMaxSize()) {
        if (expanded) ToolRail(state.tool, state.colorArgb, onSelectTool, onSelectColor)
        BoardCanvas(
          boardState = state.board,
          previews = state.previews.values.toList(),
          tool = state.tool,
          colorArgb = state.colorArgb,
          onPreview = onPreview,
          connectivityMessage = state.errorMessage ?: state.diagnostics.connectivityMessage,
          editingEnabled = state.diagnostics.editingReady,
          onCommit = { gestureId, points ->
            if (state.tool == DrawingTool.Text) {
              textAnchor = points.firstOrNull()?.packed
              textGestureId = gestureId
              text = ""
            } else onCommit(gestureId, points, "")
          },
          modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        if (expanded && showPeople) {
          ConnectedPeoplePane(
            state = state,
            onClose = { showPeople = false },
            expanded = true,
          )
        }
      }
    }
    if (!expanded && showPeople) {
      ModalBottomSheet(onDismissRequest = { showPeople = false }) {
        ConnectedPeoplePane(state = state, onClose = { showPeople = false })
      }
    }
  }

  if (confirmClear) {
    AlertDialog(
      onDismissRequest = { confirmClear = false },
      title = { Text(stringResource(R.string.clear_board_title)) },
      text = { Text(stringResource(R.string.clear_board_explanation)) },
      confirmButton = {
        Button(onClick = { confirmClear = false; onClear() }) {
          Text(stringResource(R.string.action_clear_board))
        }
      },
      dismissButton = {
        TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) }
      },
    )
  }
  textAnchor?.let { packedAnchor ->
    val points = listOf(packedAnchor.logicalPoint)
    AlertDialog(
      onDismissRequest = {
        textAnchor = null
        textGestureId = null
      },
      title = { Text(stringResource(R.string.add_text_title)) },
      text = {
        OutlinedTextField(
          value = text,
          onValueChange = { value ->
            text = value.filterNot(Char::isISOControl).take(200)
          },
          label = { Text(stringResource(R.string.text_field_label)) },
          singleLine = true,
        )
      },
      confirmButton = {
        Button(
          enabled = text.isNotBlank(),
          onClick = {
            onCommit(checkNotNull(textGestureId), points, text.trim())
            textAnchor = null
            textGestureId = null
          },
        ) { Text(stringResource(R.string.action_place_text)) }
      },
      dismissButton = {
        TextButton(onClick = {
          textAnchor = null
          textGestureId = null
        }) { Text(stringResource(R.string.action_cancel)) }
      },
    )
  }
}

private val LogicalPoint.packed: Long
  get() = (x.toLong() shl 32) or (y.toLong() and 0xFFFF_FFFFL)

private val Long.logicalPoint: LogicalPoint
  get() = LogicalPoint(x = (this shr 32).toInt(), y = toInt())

@Composable
private fun ToolRail(
  selected: DrawingTool,
  colorArgb: Int,
  onTool: (DrawingTool) -> Unit,
  onColor: (Int) -> Unit,
) {
  NavigationRail(Modifier.width(88.dp)) {
    Spacer(Modifier.height(8.dp))
    DrawingTool.entries.forEach { tool ->
      val label = tool.localizedLabel()
      NavigationRailItem(
        selected = tool == selected,
        onClick = { onTool(tool) },
        icon = { Icon(tool.icon, label) },
        label = { Text(label) },
      )
    }
    Spacer(Modifier.weight(1f))
    WHITEBOARD_COLORS.forEachIndexed { index, color ->
      ColorChoice(color, index, color == colorArgb, onColor)
    }
    Spacer(Modifier.height(8.dp))
  }
}

@Composable
private fun CompactToolbar(
  selectedTool: DrawingTool,
  colorArgb: Int,
  onTool: (DrawingTool) -> Unit,
  onColor: (Int) -> Unit,
) {
  var overflow by remember { mutableStateOf(false) }
  val overflowTool = selectedTool.takeIf { it in DrawingTool.entries.drop(4) }
  val overflowLabel = overflowTool?.localizedLabel()
  val overflowDescription = if (overflowLabel != null) {
    stringResource(R.string.selected_tool_more_options, overflowLabel)
  } else {
    stringResource(R.string.more_tools_and_colors)
  }
  val overflowStateDescription = if (overflowTool != null) {
    overflowDescription
  } else {
    stringResource(R.string.whiteboard_not_selected)
  }
  BottomAppBar {
    DrawingTool.entries.take(4).forEach { tool ->
      val isSelected = tool == selectedTool
      val label = tool.localizedLabel()
      val selectionState = stringResource(
        if (isSelected) R.string.selected_tool else R.string.whiteboard_not_selected,
      )
      IconButton(
        onClick = { onTool(tool) },
        modifier = Modifier
          .weight(1f)
          .semantics {
            selected = isSelected
            stateDescription = selectionState
          },
      ) {
        Icon(tool.icon, label, tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
      IconButton(
        onClick = { overflow = true },
        modifier = Modifier.semantics {
          selected = overflowTool != null
          stateDescription = overflowStateDescription
        },
      ) {
        Icon(
          overflowTool?.icon ?: Icons.Default.MoreVert,
          overflowDescription,
          tint = if (overflowTool != null) {
            MaterialTheme.colorScheme.primary
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
        )
      }
      DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
        DrawingTool.entries.drop(4).forEach { tool ->
          val label = tool.localizedLabel()
          val isSelected = tool == selectedTool
          DropdownMenuItem(
            text = { Text(label) },
            leadingIcon = { Icon(tool.icon, null) },
            trailingIcon = {
              if (isSelected) Icon(Icons.Default.Check, contentDescription = null)
            },
            onClick = { onTool(tool); overflow = false },
            modifier = Modifier.semantics { selected = isSelected },
          )
        }
        DropdownMenuItem(
          text = { Text(stringResource(R.string.drawing_color)) },
          leadingIcon = { Icon(Icons.Default.FormatColorFill, null, tint = Color(colorArgb)) },
          onClick = {},
          enabled = false,
        )
        WHITEBOARD_COLORS.forEachIndexed { index, color ->
          val label = stringResource(
            if (color == colorArgb) R.string.color_number_selected else R.string.color_number,
            index + 1,
          )
          DropdownMenuItem(
            text = { Text(label) },
            leadingIcon = { Icon(Icons.Default.Circle, null, tint = Color(color)) },
            onClick = { onColor(color); overflow = false },
          )
        }
      }
    }
  }
}

@Composable
private fun ColorChoice(color: Int, index: Int, isSelected: Boolean, onColor: (Int) -> Unit) {
  val description = stringResource(
    if (isSelected) R.string.color_number_selected else R.string.color_number,
    index + 1,
  )
  // The touch target is the full-width, 48dp-tall Box (meeting the accessibility minimum); the
  // 32dp swatch is only the visual affordance drawn inside it.
  Box(
    Modifier
      .fillMaxWidth()
      .height(48.dp)
      .clickable { onColor(color) }
      .semantics {
        role = Role.RadioButton
        selected = isSelected
        contentDescription = description
      },
    contentAlignment = Alignment.Center,
  ) {
    Spacer(
      Modifier
        .size(32.dp)
        .background(Color(color), CircleShape)
        .border(if (isSelected) 3.dp else 1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape),
    )
  }
}

@Composable
private fun DrawingTool.localizedLabel(): String = stringResource(
  when (this) {
    DrawingTool.Pen -> R.string.tool_pen
    DrawingTool.Line -> R.string.tool_line
    DrawingTool.Rectangle -> R.string.tool_rectangle
    DrawingTool.Ellipse -> R.string.tool_ellipse
    DrawingTool.Text -> R.string.tool_text
    DrawingTool.Eraser -> R.string.tool_eraser
  },
)

private val DrawingTool.icon: ImageVector get() = when (this) {
  DrawingTool.Pen -> Icons.Default.Edit
  DrawingTool.Line -> Icons.Default.HorizontalRule
  DrawingTool.Rectangle -> Icons.Default.Rectangle
  DrawingTool.Ellipse -> Icons.Default.Circle
  DrawingTool.Text -> Icons.Default.TextFields
  DrawingTool.Eraser -> Icons.Default.AutoFixHigh
}

/** Sample state for previews: one committed line plus a nearby peer, no live network needed. */
private fun sampleBoardUiState(): BoardUiState {
  val stamp = OperationStamp(lamport = 1, peerKey = "peerA", senderSequence = 1)
  val objectId = ObjectId(OperationId("peerA", 1))
  val line = BoardObject.Line(
    id = objectId,
    stamp = stamp,
    colorArgb = WHITEBOARD_COLORS[1],
    start = LogicalPoint(400, 400),
    end = LogicalPoint(1600, 1100),
  )
  return BoardUiState(
    board = BoardState(objects = persistentMapOf<ObjectId, BoardObject>(objectId to line)),
    tool = DrawingTool.Pen,
    colorArgb = WHITEBOARD_COLORS[1],
    diagnostics = TransportDiagnostics(
      localPeerKey = "you",
      mode = com.ditto.whiteboard.transport.TransportMode.LocalPreview,
      peers = mapOf(
        "peerB" to PeerDiagnostics(peerKey = "peerB", displayName = "Riley", colorArgb = WHITEBOARD_COLORS[2]),
      ),
    ),
  )
}

@Preview(name = "Board · light", widthDp = 900, heightDp = 500)
@Preview(name = "Board · dark", widthDp = 900, heightDp = 500, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "Board · compact", widthDp = 400, heightDp = 720)
@Composable
private fun BoardScreenPreview() {
  WhiteboardTheme {
    BoardScreen(
      state = sampleBoardUiState(),
      onSelectTool = {},
      onSelectColor = {},
      onPreview = { _, _ -> },
      onCommit = { _, _, _ -> },
      onClear = {},
      onEditProfile = {},
      onTroubleshooting = {},
    )
  }
}
