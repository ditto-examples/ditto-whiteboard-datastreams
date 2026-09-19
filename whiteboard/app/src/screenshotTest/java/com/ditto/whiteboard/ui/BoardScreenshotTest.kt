package com.ditto.whiteboard.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.ditto.whiteboard.domain.BoardObject
import com.ditto.whiteboard.domain.BoardOperation
import com.ditto.whiteboard.domain.BoardReducer
import com.ditto.whiteboard.domain.BoardState
import com.ditto.whiteboard.domain.LogicalPoint
import com.ditto.whiteboard.domain.ObjectId
import com.ditto.whiteboard.domain.OperationId
import com.ditto.whiteboard.domain.OperationStamp
import com.ditto.whiteboard.domain.UserProfile
import com.ditto.whiteboard.NearbyPermissionPrompt
import com.ditto.whiteboard.data.ProfileSettings
import com.ditto.whiteboard.transport.PeerDiagnostics
import com.ditto.whiteboard.transport.PresenceConnection
import com.ditto.whiteboard.transport.SnapshotStatus
import com.ditto.whiteboard.transport.TransportDiagnostics
import com.ditto.whiteboard.transport.TransportMode
import com.ditto.whiteboard.ui.board.BoardScreen
import com.ditto.whiteboard.ui.board.ConnectedPeoplePane
import com.ditto.whiteboard.ui.profile.ProfileSetupScreen
import com.ditto.whiteboard.ui.theme.WhiteboardTheme
import com.ditto.whiteboard.ui.troubleshooting.TroubleshootingScreen
import kotlinx.collections.immutable.persistentMapOf

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.BINARY)
@Preview(name = "400x400", widthDp = 400, heightDp = 400)
@Preview(name = "400x500", widthDp = 400, heightDp = 500)
@Preview(name = "400x1000", widthDp = 400, heightDp = 1000)
@Preview(name = "610x400", widthDp = 610, heightDp = 400)
@Preview(name = "610x500", widthDp = 610, heightDp = 500)
@Preview(name = "610x1000", widthDp = 610, heightDp = 1000)
@Preview(name = "900x400", widthDp = 900, heightDp = 400)
@Preview(name = "900x500", widthDp = 900, heightDp = 500)
@Preview(name = "900x1000", widthDp = 900, heightDp = 1000)
annotation class WhiteboardWindowSizes

@PreviewTest
@WhiteboardWindowSizes
@Composable
fun BoardWindowSizesScreenshot() {
  PreviewBoard()
}

@PreviewTest
@Preview(name = "Dark", widthDp = 900, heightDp = 500, uiMode = 0x20)
@Composable
fun BoardDarkScreenshot() {
  PreviewBoard(dark = true)
}

@PreviewTest
@Preview(name = "Large font", widthDp = 610, heightDp = 500, fontScale = 1.5f)
@Composable
fun BoardLargeFontScreenshot() {
  PreviewBoard()
}

@PreviewTest
@Preview(name = "Profile phone", widthDp = 400, heightDp = 800)
@Preview(name = "Profile short landscape", widthDp = 610, heightDp = 400)
@Composable
fun ProfileSetupScreenshots() {
  WhiteboardTheme {
    ProfileSetupScreen(
      existing = ProfileSettings("Ada Lovelace", 0xFF0057B8.toInt()),
      editing = true,
      onSave = { _, _ -> },
    )
  }
}

@PreviewTest
@Preview(name = "Permission phone", widthDp = 400, heightDp = 800)
@Composable
fun PermissionExplanationScreenshot() {
  WhiteboardTheme {
    NearbyPermissionDialog(
      prompt = NearbyPermissionPrompt.Explain,
      onRequest = {},
      onUseLocalPreview = {},
      onOpenSettings = {},
    )
  }
}

@PreviewTest
@Preview(name = "Permission denied landscape", widthDp = 610, heightDp = 400)
@Composable
fun PermissionDeniedScreenshot() {
  WhiteboardTheme {
    NearbyPermissionDialog(
      prompt = NearbyPermissionPrompt.Denied,
      onRequest = {},
      onUseLocalPreview = {},
      onOpenSettings = {},
    )
  }
}

@PreviewTest
@Preview(name = "People sheet", widthDp = 400, heightDp = 560)
@Preview(name = "People pane", widthDp = 900, heightDp = 900)
@Composable
fun ConnectedPeopleScreenshots() {
  WhiteboardTheme {
    ConnectedPeoplePane(
      state = sampleUiState(),
      onClose = {},
      expanded = true,
    )
  }
}

@PreviewTest
@Preview(name = "Troubleshooting phone", widthDp = 400, heightDp = 800)
@Preview(name = "Troubleshooting tablet", widthDp = 900, heightDp = 800)
@Composable
fun TroubleshootingScreenshots() {
  WhiteboardTheme {
    TroubleshootingScreen(
      diagnostics = sampleDiagnostics(),
      onBack = {},
    )
  }
}

@Composable
private fun PreviewBoard(dark: Boolean = false) {
  WhiteboardTheme(darkTheme = dark) {
    BoardScreen(
      state = BoardUiState(
        board = sampleBoard(),
        diagnostics = TransportDiagnostics(running = true, mode = TransportMode.NearbyMesh),
      ),
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

private fun sampleBoard(): BoardState {
  val penId = OperationId("ada", 1)
  val penStamp = OperationStamp(1, "ada", 1)
  val pen = BoardOperation.Commit(
    penId,
    penStamp,
    BoardObject.Freehand(
      ObjectId(penId), penStamp, 0xFF0057B8.toInt(),
      points = listOf(LogicalPoint(200, 250), LogicalPoint(500, 180), LogicalPoint(800, 320), LogicalPoint(1_050, 220)),
    ),
  )
  val textId = OperationId("grace", 1)
  val textStamp = OperationStamp(2, "grace", 1)
  val text = BoardOperation.Commit(
    textId,
    textStamp,
    BoardObject.Text(ObjectId(textId), textStamp, 0xFF007A3D.toInt(), LogicalPoint(560, 650), "Hello, mesh!", 64),
  )
  return BoardReducer.merge(BoardState(), listOf(pen, text))
}

private fun sampleUiState(): BoardUiState = BoardUiState(
  board = sampleBoard().copy(
    profiles = persistentMapOf(
      "local-peer" to UserProfile("local-peer", "Ada Lovelace", 0xFF0057B8.toInt()),
      "grace-peer" to UserProfile("grace-peer", "Grace Hopper", 0xFF007A3D.toInt()),
    ),
  ),
  diagnostics = sampleDiagnostics(),
)

private fun sampleDiagnostics(): TransportDiagnostics {
  val grace = PeerDiagnostics(
    peerKey = "grace-peer",
    displayName = "Grace Hopper",
    colorArgb = 0xFF007A3D.toInt(),
    transports = setOf("Bluetooth LE", "LAN"),
    liveConnected = true,
    stateConnected = true,
    snapshotStatus = SnapshotStatus.Acknowledged,
    snapshotProgress = 1f,
    transmitMessagesPerSecond = 14.0,
    receiveMessagesPerSecond = 11.0,
  )
  return TransportDiagnostics(
    localPeerKey = "local-peer",
    running = true,
    editingReady = true,
    mode = TransportMode.NearbyMesh,
    peers = mapOf(grace.peerKey to grace),
    presenceConnections = setOf(
      PresenceConnection("local-peer", grace.peerKey, "Bluetooth LE"),
      PresenceConnection("local-peer", grace.peerKey, "LAN"),
    ),
  )
}
