package com.ditto.whiteboard.ui.troubleshooting

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.ditto.whiteboard.transport.PeerDiagnostics
import com.ditto.whiteboard.transport.PeerPingException
import com.ditto.whiteboard.transport.PeerPingFailure
import com.ditto.whiteboard.transport.TransportDiagnostics
import com.ditto.whiteboard.transport.TransportMode
import com.ditto.whiteboard.ui.theme.WhiteboardTheme
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PeerPingCardTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val nearby = TransportDiagnostics(
    running = true, mode = TransportMode.NearbyMesh,
    peers = mapOf("peer1234" to PeerDiagnostics(peerKey = "peer1234", displayName = "Ada")),
  )

  private fun selectAndRun() {
    compose.onNodeWithText("Select a peer").performClick()
    compose.onNodeWithText("Ada · peer1234").performClick()
    compose.onNodeWithTag("runPeerPingButton").performClick()
  }

  @Test fun localPreviewCannotStartPing() {
    compose.setContent { WhiteboardTheme { PeerPingCard(TransportDiagnostics()) { _, _ -> error("Must not run") } } }
    compose.onNodeWithTag("runPeerPingButton").assertIsNotEnabled()
  }

  @Test fun verifiedSamplesAndCorrectTargetAreDisplayed() {
    var target = ""
    compose.setContent {
      WhiteboardTheme {
        Column(Modifier.verticalScroll(rememberScrollState())) {
          PeerPingCard(nearby) { peer, sample -> target = peer; repeat(5) { sample(1.25) } }
        }
      }
    }
    selectAndRun()
    compose.onNodeWithText("5 of 5 echoes verified").assertIsDisplayed()
    compose.onNodeWithText("Ping completed.").performScrollTo().assertIsDisplayed()
    compose.onNodeWithTag("runPeerPingButton").assertIsEnabled()
    compose.runOnIdle { assertEquals("peer1234", target) }
  }

  @Test fun timeoutKeepsEarlierSamplesAndAllowsAnotherRun() {
    compose.setContent {
      WhiteboardTheme {
        PeerPingCard(nearby) { _, sample ->
          sample(1.0)
          throw PeerPingException(PeerPingFailure.TimedOut)
        }
      }
    }
    selectAndRun()
    compose.onNodeWithText("1 of 5 echoes verified").assertIsDisplayed()
    compose.onNodeWithText("No echo within 5 seconds. The peer may have disconnected or paused.").assertIsDisplayed()
    compose.onNodeWithTag("runPeerPingButton").assertIsEnabled()
  }

  @Test fun cancelStopsPendingRun() {
    compose.setContent { WhiteboardTheme { PeerPingCard(nearby) { _, _ -> awaitCancellation() } } }
    selectAndRun()
    compose.onNodeWithText("Cancel ping").performClick()
    compose.onNodeWithText("Ping cancelled.").assertIsDisplayed()
    compose.onNodeWithTag("runPeerPingButton").assertIsEnabled()
  }
}
