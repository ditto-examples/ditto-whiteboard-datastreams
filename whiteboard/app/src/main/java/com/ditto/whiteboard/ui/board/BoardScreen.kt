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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardScreen(
  state: BoardUiState,
  onSelectTool: (DrawingTool) -> Unit,
  onSelectColor: (Int) -> Unit,
  onPreview: (List<LogicalPoint>) -> Unit,
  onCommit: (List<LogicalPoint>, String) -> Unit,
  onClear: () -> Unit,
  onEditProfile: () -> Unit,
  onTroubleshooting: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var confirmClear by remember { mutableStateOf(false) }
  var textAnchor by remember { mutableStateOf<List<LogicalPoint>?>(null) }
  var text by remember { mutableStateOf("") }
  var showPeople by rememberSaveable { mutableStateOf(false) }
  val connectedCount = remember(state.board.profiles, state.diagnostics, state.colorArgb) {
    state.connectedPeople().size
  }

  BoxWithConstraints(modifier.fillMaxSize()) {
    val expanded = maxWidth >= 840.dp && maxHeight >= 480.dp
    Scaffold(
      modifier = Modifier.fillMaxSize(),
      topBar = {
        TopAppBar(
          title = { Text("Ditto Whiteboard", maxLines = 1, overflow = TextOverflow.Ellipsis) },
          actions = {
            IconButton(onClick = { showPeople = !showPeople }) {
              BadgedBox(badge = { Badge { Text(connectedCount.toString()) } }) {
                Icon(Icons.Default.Groups, "Connected people, $connectedCount")
              }
            }
            IconButton(onClick = onEditProfile) { Icon(Icons.Default.Person, "Edit profile") }
            IconButton(onClick = onTroubleshooting) { Icon(Icons.Default.Troubleshoot, "Troubleshooting") }
            IconButton(onClick = { confirmClear = true }) { Icon(Icons.Default.DeleteSweep, "Clear board") }
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
          connectivityMessage = state.diagnostics.connectivityMessage,
          onCommit = { points ->
            if (state.tool == DrawingTool.Text) {
              textAnchor = points
              text = ""
            } else onCommit(points, "")
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
      title = { Text("Clear the shared board?") },
      text = { Text("This removes every object for all connected collaborators and cannot be undone.") },
      confirmButton = { Button(onClick = { confirmClear = false; onClear() }) { Text("Clear board") } },
      dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
  }
  textAnchor?.let { points ->
    AlertDialog(
      onDismissRequest = { textAnchor = null },
      title = { Text("Add text") },
      text = {
        OutlinedTextField(
          value = text,
          onValueChange = { text = it.take(200) },
          label = { Text("Text") },
          minLines = 2,
        )
      },
      confirmButton = {
        Button(
          enabled = text.isNotBlank(),
          onClick = { onCommit(points, text.trim()); textAnchor = null },
        ) { Text("Place text") }
      },
      dismissButton = { TextButton(onClick = { textAnchor = null }) { Text("Cancel") } },
    )
  }
}

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
      NavigationRailItem(
        selected = tool == selected,
        onClick = { onTool(tool) },
        icon = { Icon(tool.icon, tool.label) },
        label = { Text(tool.label) },
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
  BottomAppBar {
    DrawingTool.entries.take(4).forEach { tool ->
      val isSelected = tool == selectedTool
      IconButton(
        onClick = { onTool(tool) },
        modifier = Modifier
          .weight(1f)
          .semantics {
            selected = isSelected
            stateDescription = if (isSelected) "Selected tool" else "Not selected"
          },
      ) {
        Icon(tool.icon, tool.label, tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
      IconButton(onClick = { overflow = true }) { Icon(Icons.Default.MoreVert, "More tools and colors") }
      DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
        DrawingTool.entries.drop(4).forEach { tool ->
          DropdownMenuItem(
            text = { Text(tool.label) },
            leadingIcon = { Icon(tool.icon, null) },
            onClick = { onTool(tool); overflow = false },
          )
        }
        DropdownMenuItem(
          text = { Text("Drawing color") },
          leadingIcon = { Icon(Icons.Default.FormatColorFill, null, tint = Color(colorArgb)) },
          onClick = {},
          enabled = false,
        )
        WHITEBOARD_COLORS.forEachIndexed { index, color ->
          DropdownMenuItem(
            text = { Text("Color ${index + 1}${if (color == colorArgb) " · selected" else ""}") },
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
        contentDescription = "Drawing color ${index + 1}${if (isSelected) ", selected" else ""}"
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

private val DrawingTool.label: String get() = when (this) {
  DrawingTool.Pen -> "Pen"
  DrawingTool.Line -> "Line"
  DrawingTool.Rectangle -> "Rectangle"
  DrawingTool.Ellipse -> "Ellipse"
  DrawingTool.Text -> "Text"
  DrawingTool.Eraser -> "Eraser"
}

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
    board = BoardState(objects = mapOf(objectId to line)),
    tool = DrawingTool.Pen,
    colorArgb = WHITEBOARD_COLORS[1],
    diagnostics = TransportDiagnostics(
      localPeerKey = "you",
      mode = "Preview",
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
      onPreview = {},
      onCommit = { _, _ -> },
      onClear = {},
      onEditProfile = {},
      onTroubleshooting = {},
    )
  }
}
