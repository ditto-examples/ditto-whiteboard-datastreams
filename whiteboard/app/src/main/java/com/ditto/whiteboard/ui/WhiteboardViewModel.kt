package com.ditto.whiteboard.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ditto.whiteboard.data.BoardSession
import com.ditto.whiteboard.data.ProfileRepository
import com.ditto.whiteboard.domain.BoardState
import com.ditto.whiteboard.domain.DrawingTool
import com.ditto.whiteboard.domain.LivePreview
import com.ditto.whiteboard.domain.LogicalPoint
import com.ditto.whiteboard.transport.TransportDiagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BoardUiState(
  val board: BoardState = BoardState(),
  val previews: Map<String, LivePreview> = emptyMap(),
  val tool: DrawingTool = DrawingTool.Pen,
  val colorArgb: Int = WHITEBOARD_COLORS.first(),
  val diagnostics: TransportDiagnostics = TransportDiagnostics(),
)

val WHITEBOARD_COLORS = listOf(
  0xFF1D1B20.toInt(),
  0xFF0057B8.toInt(),
  0xFF007A3D.toInt(),
  0xFFC62828.toInt(),
  0xFF7B1FA2.toInt(),
  0xFFF57C00.toInt(),
  0xFF00838F.toInt(),
  0xFFFFFFFF.toInt(),
)

class WhiteboardViewModel(
  val profiles: ProfileRepository,
  private val session: BoardSession,
) : ViewModel() {
  private val selectedTool = MutableStateFlow(DrawingTool.Pen)
  private val selectedColor = MutableStateFlow(WHITEBOARD_COLORS.first())
  // The profile's default color seeds the toolbar only on the first session start; later profile
  // edits must not clobber whatever color the user has since selected mid-session.
  private var colorSeeded = false

  val uiState: StateFlow<BoardUiState> = combine(
    session.boardState,
    session.previews,
    selectedTool,
    selectedColor,
    session.diagnostics,
  ) { board, previews, tool, color, diagnostics ->
    BoardUiState(board, previews, tool, color, diagnostics)
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BoardUiState())

  fun startSession(displayName: String, colorArgb: Int) {
    if (!colorSeeded) {
      selectedColor.value = colorArgb
      colorSeeded = true
    }
    viewModelScope.launch { session.start(displayName, colorArgb) }
  }

  fun saveProfile(displayName: String, colorArgb: Int, onSaved: () -> Unit) {
    viewModelScope.launch {
      profiles.save(displayName, colorArgb)
      startSession(displayName.trim(), colorArgb)
      onSaved()
    }
  }

  fun selectTool(tool: DrawingTool) { selectedTool.value = tool }
  fun selectColor(colorArgb: Int) { selectedColor.value = colorArgb }
  fun preview(points: List<LogicalPoint>) = session.preview(selectedTool.value, selectedColor.value, points)
  fun commit(points: List<LogicalPoint>, text: String = "") =
    session.commit(selectedTool.value, selectedColor.value, points, text)
  fun clear() = session.clear()

  class Factory(
    private val profiles: ProfileRepository,
    private val session: BoardSession,
  ) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
      WhiteboardViewModel(profiles, session) as T
  }
}
