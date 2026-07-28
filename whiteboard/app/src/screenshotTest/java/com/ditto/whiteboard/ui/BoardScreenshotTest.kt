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
import com.ditto.whiteboard.transport.TransportDiagnostics
import com.ditto.whiteboard.ui.board.BoardScreen
import com.ditto.whiteboard.ui.theme.WhiteboardTheme

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

@Composable
private fun PreviewBoard(dark: Boolean = false) {
  WhiteboardTheme(darkTheme = dark) {
    BoardScreen(
      state = BoardUiState(
        board = sampleBoard(),
        diagnostics = TransportDiagnostics(running = true, mode = "Ditto nearby mesh"),
      ),
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
