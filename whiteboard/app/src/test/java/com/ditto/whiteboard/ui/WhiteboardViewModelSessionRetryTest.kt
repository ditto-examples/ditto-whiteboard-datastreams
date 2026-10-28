package com.ditto.whiteboard.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ditto.whiteboard.data.TEST_BOARD_SESSION_MESSAGES
import com.ditto.whiteboard.data.BoardSession
import com.ditto.whiteboard.data.ProfileRepository
import com.ditto.whiteboard.domain.LivePreview
import com.ditto.whiteboard.domain.BoardOperation
import com.ditto.whiteboard.domain.UserProfile
import com.ditto.whiteboard.transport.TransportDiagnostics
import com.ditto.whiteboard.transport.TransportEvent
import com.ditto.whiteboard.transport.WhiteboardTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WhiteboardViewModelSessionRetryTest {

  private val messages = WhiteboardViewModelMessages(
    sessionStartFailed = "start failed",
    profileSessionUpdateFailed = "update failed",
    profileSaveFailed = "save failed",
  )
  private val scope = CoroutineScope(Dispatchers.Unconfined)

  @Before
  fun setUp() {
    Dispatchers.setMain(UnconfinedTestDispatcher())
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test(timeout = 10_000)
  fun failedSessionStartIsRetriableWithoutProcessRestart() {
    var startAttempts = 0
    val transport = object : WhiteboardTransport {
      override val localPeerKey = "retry-test-peer"
      override val events: Flow<TransportEvent> = emptyFlow()
      override val diagnostics: StateFlow<TransportDiagnostics> =
        MutableStateFlow(TransportDiagnostics(editingReady = false))

      override suspend fun start(profile: UserProfile) {
        startAttempts++
        if (startAttempts == 1) error("transport start failed")
      }

      override suspend fun sendReliable(operation: BoardOperation) = true
      override fun sendLive(preview: LivePreview) = Unit
      override fun close() = Unit
    }
    val session = BoardSession(transport, scope, TEST_BOARD_SESSION_MESSAGES)
    val context = ApplicationProvider.getApplicationContext<Context>()
    val viewModel = WhiteboardViewModel(
      profiles = ProfileRepository(context, scope),
      sessionProvider = { session },
      messages = messages,
    )
    // The unconfined collector may append while the polling assertion reads the
    // stream. A regular MutableList made this retry test intermittently throw
    // ConcurrentModificationException on CI instead of asserting its contract.
    val observed = CopyOnWriteArrayList<BoardUiState>()
    val collector = scope.launch { viewModel.uiState.toList(observed) }

    viewModel.startSession("Ada", com.ditto.whiteboard.ui.WHITEBOARD_COLORS.first())

    awaitObserved(observed) { it.startFailed }
    assertTrue(
      "the session-start failure must surface as a retryable state, not only a pill message",
      observed.last().startFailed,
    )

    viewModel.retrySessionStart()

    awaitObserved(observed) { !it.startFailed }
    assertEquals("retry must re-enter the session start path", 2, startAttempts)
    assertFalse(viewModel.uiState.value.startFailed)

    collector.cancel()
  }

  private fun awaitObserved(
    states: List<BoardUiState>,
    timeoutMillis: Long = 5_000,
    predicate: (BoardUiState) -> Boolean,
  ) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (!states.any(predicate)) {
      if (System.currentTimeMillis() > deadline) {
        throw AssertionError(
          "expected UI state never arrived; observed startFailed=${states.map { it.startFailed }}",
        )
      }
      Thread.sleep(10)
    }
  }
}
