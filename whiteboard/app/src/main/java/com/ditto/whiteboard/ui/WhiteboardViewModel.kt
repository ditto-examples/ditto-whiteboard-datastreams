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
import com.ditto.whiteboard.domain.WHITEBOARD_PALETTE
import com.ditto.whiteboard.domain.isApprovedWhiteboardColor
import com.ditto.whiteboard.domain.normalizeWhiteboardColor
import com.ditto.whiteboard.transport.TransportDiagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.ditto.whiteboard.util.runSuspendCatchingPreservingCancellation

data class BoardUiState(
  val board: BoardState = BoardState(),
  val previews: Map<String, LivePreview> = emptyMap(),
  val tool: DrawingTool = DrawingTool.Pen,
  val colorArgb: Int = WHITEBOARD_COLORS.first(),
  val diagnostics: TransportDiagnostics = TransportDiagnostics(editingReady = false),
  val errorMessage: String? = null,
  val startFailed: Boolean = false,
)

val WHITEBOARD_COLORS: List<Int> = WHITEBOARD_PALETTE

data class WhiteboardViewModelMessages(
  val sessionStartFailed: String,
  val profileSessionUpdateFailed: String,
  val profileSaveFailed: String,
)

@OptIn(ExperimentalCoroutinesApi::class)
class WhiteboardViewModel(
  val profiles: ProfileRepository,
  private val sessionProvider: () -> BoardSession,
  private val messages: WhiteboardViewModelMessages,
) : ViewModel() {
  private val selectedTool = MutableStateFlow(DrawingTool.Pen)
  private val selectedColor = MutableStateFlow(WHITEBOARD_COLORS.first())
  private val actionError = MutableStateFlow<String?>(null)
  private val sessionState = MutableStateFlow<BoardSession?>(null)
  // Latched only when the initial session start fails, so the app can offer an explicit retry
  // affordance instead of an empty board whose only hint was an ellipsized pill message.
  private val startFailed = MutableStateFlow(false)
  private var lastStartInput: Pair<String, Int>? = null
  private val sessionMutex = Mutex()
  private var ownedSession: BoardSession? = null
  // The profile's default color seeds the toolbar only on the first session start; later profile
  // edits must not clobber whatever color the user has since selected mid-session.
  private var colorSeeded = false

  val uiState: StateFlow<BoardUiState> = sessionState.flatMapLatest { session ->
    if (session == null) {
      combine(selectedTool, selectedColor, actionError, startFailed) { tool, color, error, failed ->
        BoardUiState(tool = tool, colorArgb = color, errorMessage = error, startFailed = failed)
      }
    } else {
      val boardUiState = combine(
        session.boardState,
        session.previews,
        selectedTool,
        selectedColor,
        session.diagnostics,
      ) { board, previews, tool, color, diagnostics ->
        BoardUiState(board, previews, tool, color, diagnostics)
      }
      combine(boardUiState, session.error, actionError) { state, sessionError, currentActionError ->
        state.copy(errorMessage = currentActionError ?: sessionError)
      }
    }
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BoardUiState())

  private suspend fun session(): BoardSession =
    ownedSession ?: sessionMutex.withLock {
      ownedSession ?: withContext(Dispatchers.Default) { sessionProvider() }
        .also { ownedSession = it }
    }

  fun startSession(displayName: String, colorArgb: Int) {
    val normalizedColor = normalizeWhiteboardColor(colorArgb)
    if (!colorSeeded) {
      selectedColor.value = normalizedColor
      colorSeeded = true
    }
    lastStartInput = displayName to normalizedColor
    viewModelScope.launch {
      val activeSession = session()
      runSuspendCatchingPreservingCancellation {
        activeSession.start(displayName, normalizedColor)
      }
        .onSuccess {
          sessionState.value = activeSession
          actionError.value = null
          startFailed.value = false
        }
        .onFailure {
          actionError.value = messages.sessionStartFailed
          startFailed.value = true
        }
    }
  }

  /**
   * Retries the initial session start. The failed attempt left the session half-started on
   * purpose — the transport latched `started` before the failing reconcile — so re-entering
   * `start` is effective, which a plain re-composition is not (the [startSession] effect keys
   * do not change on their own).
   */
  fun retrySessionStart() {
    val input = lastStartInput ?: return
    startFailed.value = false
    startSession(input.first, input.second)
  }

  fun saveProfile(displayName: String, colorArgb: Int, onSaved: () -> Unit) {
    viewModelScope.launch {
      runSuspendCatchingPreservingCancellation {
        profiles.save(displayName, colorArgb)
      }.onSuccess {
        actionError.value = null
        val activeSession = sessionState.value
        if (activeSession == null) {
          onSaved()
        } else {
          runSuspendCatchingPreservingCancellation {
            activeSession.start(displayName.trim(), colorArgb)
          }
            .onSuccess { onSaved() }
            .onFailure {
              actionError.value = messages.profileSessionUpdateFailed
            }
        }
      }.onFailure {
        actionError.value = messages.profileSaveFailed
      }
    }
  }

  fun selectTool(tool: DrawingTool) { selectedTool.value = tool }
  fun selectColor(colorArgb: Int) {
    if (isApprovedWhiteboardColor(colorArgb)) selectedColor.value = colorArgb
  }
  fun preview(gestureId: String, points: List<LogicalPoint>) =
    sessionState.value?.preview(gestureId, selectedTool.value, selectedColor.value, points)
  fun commit(gestureId: String, points: List<LogicalPoint>, text: String = "") =
    sessionState.value?.commit(selectedTool.value, selectedColor.value, points, text, gestureId)
  fun clear() = sessionState.value?.clear()

  class Factory(
    private val profiles: ProfileRepository,
    private val sessionProvider: () -> BoardSession,
    private val messages: WhiteboardViewModelMessages,
  ) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
      WhiteboardViewModel(profiles, sessionProvider, messages) as T
  }
}
