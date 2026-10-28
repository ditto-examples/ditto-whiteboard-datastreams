package com.ditto.whiteboard.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.filled.ViewSidebar
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Rectangle
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.outlined.Hub
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.ditto.whiteboard.data.ProfileSettings
import com.ditto.whiteboard.domain.BoardState
import com.ditto.whiteboard.domain.BoardTextFont
import com.ditto.whiteboard.domain.DEFAULT_TEXT_FONT
import com.ditto.whiteboard.domain.DEFAULT_TEXT_SIZE
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
import com.ditto.whiteboard.ui.troubleshooting.presencegraph.PresenceGraphUiState
import kotlinx.collections.immutable.persistentMapOf

private val drawingToolsInInterface = listOf(
  DrawingTool.Pen,
  DrawingTool.Line,
  DrawingTool.Rectangle,
  DrawingTool.Ellipse,
  DrawingTool.Text,
  DrawingTool.Hand,
  DrawingTool.Eraser,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardScreen(
  state: BoardUiState,
  onSelectTool: (DrawingTool) -> Unit,
  onSelectColor: (Int) -> Unit,
  onPreview: (String, List<LogicalPoint>) -> Unit,
  onCommit: (String, List<LogicalPoint>, String) -> Unit,
  onClear: () -> Unit,
  modifier: Modifier = Modifier,
  onCommitText: (String, List<LogicalPoint>, String, BoardTextFont, Int) -> Unit = { gestureId, points, text, _, _ ->
    onCommit(gestureId, points, text)
  },
  onPresenceGraph: () -> Unit = {},
  onPeerList: () -> Unit = {},
  profile: ProfileSettings? = null,
  onSaveProfile: (String, Int) -> Unit = { _, _ -> },
  presenceGraphState: PresenceGraphUiState = PresenceGraphUiState.Initializing,
) {
  var confirmClear by rememberSaveable { mutableStateOf(false) }
  var textAnchor by rememberSaveable { mutableStateOf<Long?>(null) }
  var textGestureId by rememberSaveable { mutableStateOf<String?>(null) }
  var text by rememberSaveable { mutableStateOf("") }
  var textFont by rememberSaveable { mutableStateOf(DEFAULT_TEXT_FONT) }
  var textSize by rememberSaveable { mutableIntStateOf(DEFAULT_TEXT_SIZE) }
  var showSidebar by rememberSaveable { mutableStateOf(false) }
  var sidebarSection by rememberSaveable { mutableStateOf(SidebarSection.People) }

  BoxWithConstraints(modifier.fillMaxSize()) {
    // Account for Scaffold/top-system insets as well as seven tools plus eight 48dp color targets.
    // Short tablets/foldables keep the compact overflow toolbar so every action remains reachable.
    val expanded = maxWidth >= 840.dp && maxHeight >= 900.dp
    Scaffold(
      modifier = Modifier.fillMaxSize(),
      topBar = {
        TopAppBar(
          title = { Text(stringResource(R.string.board_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
          actions = {
            IconButton(onClick = onPresenceGraph) {
              Icon(Icons.Outlined.Hub, stringResource(R.string.action_presence_graph))
            }
            IconButton(onClick = onPeerList) {
              Icon(Icons.AutoMirrored.Outlined.List, stringResource(R.string.action_peer_list))
            }
            IconButton(
              onClick = { confirmClear = true },
              enabled = state.diagnostics.editingReady,
            ) { Icon(Icons.Default.DeleteSweep, stringResource(R.string.action_clear_board)) }
            IconButton(onClick = { showSidebar = !showSidebar }) {
              Icon(Icons.AutoMirrored.Filled.ViewSidebar, stringResource(R.string.sidebar_toggle))
            }
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
            } else if (state.tool != DrawingTool.Hand) onCommit(gestureId, points, "")
          },
          modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        if (expanded && showSidebar) {
          Surface(
            modifier = Modifier.width(380.dp).fillMaxHeight(),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 2.dp,
          ) {
            BoardSidebarPanel(
              state = state,
              section = sidebarSection,
              onSectionChange = { sidebarSection = it },
              onDismiss = { showSidebar = false },
              onSaveProfile = onSaveProfile,
              profile = profile,
              profileErrorMessage = state.errorMessage,
              presenceGraphState = presenceGraphState,
            )
          }
        }
      }
    }
    if (!expanded && showSidebar) {
      // The unified panel is content-rich (profile form, troubleshooting graph), so open fully
      // expanded instead of resting at the half-height anchor with the section clipped.
      ModalBottomSheet(
        onDismissRequest = { showSidebar = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
      ) {
        BoardSidebarPanel(
          state = state,
          section = sidebarSection,
          onSectionChange = { sidebarSection = it },
          onDismiss = { showSidebar = false },
          onSaveProfile = onSaveProfile,
          profile = profile,
          profileErrorMessage = state.errorMessage,
          presenceGraphState = presenceGraphState,
        )
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
        TextStyleEditor(
          text = text,
          font = textFont,
          size = textSize,
          onTextChange = { value -> text = value.filterNot(Char::isISOControl).take(200) },
          onFontChange = { textFont = it },
          onSizeChange = { textSize = it },
        )
      },
      confirmButton = {
        Button(
          enabled = text.isNotBlank(),
          onClick = {
            onCommitText(checkNotNull(textGestureId), points, text.trim(), textFont, textSize)
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
private fun TextStyleEditor(
  text: String,
  font: BoardTextFont,
  size: Int,
  onTextChange: (String) -> Unit,
  onFontChange: (BoardTextFont) -> Unit,
  onSizeChange: (Int) -> Unit,
) {
  var fontMenuExpanded by remember { mutableStateOf(false) }
  var sizeMenuExpanded by remember { mutableStateOf(false) }
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    OutlinedTextField(
      value = text,
      onValueChange = onTextChange,
      label = { Text(stringResource(R.string.text_field_label)) },
      modifier = Modifier.fillMaxWidth(),
      minLines = 2,
      maxLines = 4,
    )
    Box(Modifier.fillMaxWidth()) {
      OutlinedTextField(
        value = stringResource(font.labelRes()),
        onValueChange = {},
        label = { Text(stringResource(R.string.text_font_label)) },
        modifier = Modifier.fillMaxWidth().clickable { fontMenuExpanded = true },
        readOnly = true,
      )
      DropdownMenu(
        expanded = fontMenuExpanded,
        onDismissRequest = { fontMenuExpanded = false },
      ) {
        BoardTextFont.entries.forEach { option ->
          DropdownMenuItem(
            text = { Text(stringResource(option.labelRes())) },
            onClick = {
              onFontChange(option)
              fontMenuExpanded = false
            },
          )
        }
      }
    }
    Box(Modifier.fillMaxWidth()) {
      OutlinedTextField(
        value = stringResource(R.string.text_size_points, size),
        onValueChange = {},
        label = { Text(stringResource(R.string.text_size_label)) },
        modifier = Modifier.fillMaxWidth().clickable { sizeMenuExpanded = true },
        readOnly = true,
      )
      DropdownMenu(
        expanded = sizeMenuExpanded,
        onDismissRequest = { sizeMenuExpanded = false },
      ) {
        textSizes.forEach { option ->
          DropdownMenuItem(
            text = { Text(stringResource(R.string.text_size_points, option)) },
            onClick = {
              onSizeChange(option)
              sizeMenuExpanded = false
            },
          )
        }
      }
    }
  }
}

private val textSizes = listOf(24, 36, 48, 64, 80, 96)

private fun BoardTextFont.labelRes(): Int = when (this) {
  BoardTextFont.System -> R.string.text_font_system
  BoardTextFont.Rounded -> R.string.text_font_rounded
  BoardTextFont.Serif -> R.string.text_font_serif
  BoardTextFont.Monospaced -> R.string.text_font_monospaced
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
    drawingToolsInInterface.forEach { tool ->
      val label = tool.localizedLabel()
      NavigationRailItem(
        selected = tool == selected,
        onClick = { onTool(tool) },
        icon = { Icon(tool.icon, label) },
        label = { Text(label) },
        colors = NavigationRailItemDefaults.colors(
          selectedIconColor = MaterialTheme.colorScheme.onPrimary,
          selectedTextColor = MaterialTheme.colorScheme.onPrimary,
          indicatorColor = MaterialTheme.colorScheme.primary,
        ),
      )
    }
    Spacer(Modifier.weight(1f))
    WHITEBOARD_COLORS.forEach { color ->
      ColorChoice(color, color == colorArgb, onColor)
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
  BottomAppBar {
    CompactColorMenu(
      colorArgb = colorArgb,
      onColor = onColor,
      modifier = Modifier.weight(1f),
    )
    CompactToolMenu(
      labelRes = R.string.compact_menu_shapes,
      icon = Icons.Default.Rectangle,
      tools = listOf(DrawingTool.Line, DrawingTool.Rectangle, DrawingTool.Ellipse),
      selectedTool = selectedTool,
      onTool = onTool,
      modifier = Modifier.weight(1f),
    )
    CompactToolMenu(
      labelRes = R.string.compact_menu_text_and_tools,
      icon = Icons.Default.TextFields,
      tools = listOf(DrawingTool.Pen, DrawingTool.Text, DrawingTool.Eraser, DrawingTool.Hand),
      selectedTool = selectedTool,
      onTool = onTool,
      modifier = Modifier.weight(1f),
    )
  }
}

@Composable
private fun CompactColorMenu(
  colorArgb: Int,
  onColor: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(false) }
  val label = stringResource(R.string.compact_menu_color)
  Box(modifier, contentAlignment = Alignment.Center) {
    IconButton(onClick = { expanded = true }) {
      Icon(Icons.Default.FormatColorFill, label, tint = Color(colorArgb))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      Column(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        WHITEBOARD_COLORS.chunked(4).forEach { row ->
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { color ->
              PaletteColorSwatch(
                color = color,
                isSelected = color == colorArgb,
                onClick = {
                  onColor(color)
                  expanded = false
                },
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun PaletteColorSwatch(
  color: Int,
  isSelected: Boolean,
  onClick: () -> Unit,
) {
  val colorName = stringResource(whiteboardColorNameResource(color))
  val description = stringResource(
    if (isSelected) R.string.color_name_selected else R.string.color_name,
    colorName,
  )
  Box(
    modifier = Modifier
      .size(48.dp)
      .background(Color(color), CircleShape)
      .border(
        if (isSelected) 3.dp else 1.dp,
        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        CircleShape,
      )
      .clickable(onClick = onClick)
      .semantics {
        role = Role.RadioButton
        selected = isSelected
        contentDescription = description
      },
    contentAlignment = Alignment.Center,
  ) {
    if (isSelected) Icon(Icons.Default.Check, contentDescription = null, tint = Color.White)
  }
}

@Composable
private fun CompactToolMenu(
  labelRes: Int,
  icon: ImageVector,
  tools: List<DrawingTool>,
  selectedTool: DrawingTool,
  onTool: (DrawingTool) -> Unit,
  modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(false) }
  val label = stringResource(labelRes)
  val isSelected = selectedTool in tools
  val selectionState = stringResource(
    if (isSelected) R.string.selected_tool else R.string.whiteboard_not_selected,
  )
  Box(modifier, contentAlignment = Alignment.Center) {
    IconButton(
      onClick = { expanded = true },
      modifier = Modifier
        .background(
          if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
          CircleShape,
        )
        .semantics {
          selected = isSelected
          stateDescription = selectionState
        },
    ) {
      Icon(
        imageVector = icon,
        contentDescription = label,
        tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      tools.forEach { tool ->
        val toolLabel = tool.localizedLabel()
        val toolSelected = tool == selectedTool
        DropdownMenuItem(
          text = { Text(toolLabel) },
          leadingIcon = { Icon(tool.icon, null) },
          trailingIcon = {
            if (toolSelected) Icon(Icons.Default.Check, contentDescription = null)
          },
          onClick = { onTool(tool); expanded = false },
          modifier = Modifier.semantics { selected = toolSelected },
        )
      }
    }
  }
}

@Composable
private fun ColorChoice(color: Int, isSelected: Boolean, onColor: (Int) -> Unit) {
  val colorName = stringResource(whiteboardColorNameResource(color))
  val description = stringResource(
    if (isSelected) R.string.color_name_selected else R.string.color_name,
    colorName,
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

private fun whiteboardColorNameResource(color: Int): Int = when (color) {
  WHITEBOARD_COLORS[0] -> R.string.color_charcoal
  WHITEBOARD_COLORS[1] -> R.string.color_blue
  WHITEBOARD_COLORS[2] -> R.string.color_green
  WHITEBOARD_COLORS[3] -> R.string.color_red
  WHITEBOARD_COLORS[4] -> R.string.color_purple
  WHITEBOARD_COLORS[5] -> R.string.color_orange
  WHITEBOARD_COLORS[6] -> R.string.color_teal
  WHITEBOARD_COLORS[7] -> R.string.color_magenta
  else -> R.string.color_custom
}

@Composable
private fun DrawingTool.localizedLabel(): String = stringResource(
  when (this) {
    DrawingTool.Pen -> R.string.tool_pen
    DrawingTool.Line -> R.string.tool_line
    DrawingTool.Rectangle -> R.string.tool_rectangle
    DrawingTool.Ellipse -> R.string.tool_ellipse
    DrawingTool.Text -> R.string.tool_text
    DrawingTool.Hand -> R.string.tool_hand
    DrawingTool.Eraser -> R.string.tool_eraser
  },
)

private val DrawingTool.icon: ImageVector get() = when (this) {
  DrawingTool.Pen -> Icons.Default.Edit
  DrawingTool.Line -> Icons.Default.HorizontalRule
  DrawingTool.Rectangle -> Icons.Default.Rectangle
  DrawingTool.Ellipse -> Icons.Default.Circle
  DrawingTool.Text -> Icons.Default.TextFields
  DrawingTool.Hand -> Icons.Default.PanTool
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
    )
  }
}
