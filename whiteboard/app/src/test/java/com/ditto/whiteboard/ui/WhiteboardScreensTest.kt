package com.ditto.whiteboard.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.ditto.whiteboard.domain.BoardState
import com.ditto.whiteboard.domain.UserProfile
import com.ditto.whiteboard.transport.PeerDiagnostics
import com.ditto.whiteboard.transport.TransportDiagnostics
import com.ditto.whiteboard.ui.board.BoardScreen
import com.ditto.whiteboard.ui.profile.ProfileSetupScreen
import com.ditto.whiteboard.ui.theme.WhiteboardTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WhiteboardScreensTest {
  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun onboardingValidatesAndSavesProfile() {
    var savedName = ""
    composeTestRule.setContent {
      WhiteboardTheme {
        ProfileSetupScreen(
          existing = null,
          editing = false,
          onSave = { name, _ -> savedName = name },
        )
      }
    }

    composeTestRule.onNodeWithText("Display name").performTextInput("Ada")
    composeTestRule.onNodeWithText("Join the board").performClick()
    composeTestRule.runOnIdle { assertEquals("Ada", savedName) }
  }

  @Test
  fun onboardingRestoresTypedNameAfterRecreation() {
    val restorationTester = StateRestorationTester(composeTestRule)
    restorationTester.setContent {
      WhiteboardTheme {
        ProfileSetupScreen(existing = null, editing = false, onSave = { _, _ -> })
      }
    }

    composeTestRule.onNodeWithText("Display name").performTextInput("Ada")
    restorationTester.emulateSavedInstanceStateRestore()
    composeTestRule.onNodeWithText("Ada").assertIsDisplayed()
  }

  @Test
  fun editingProfileHasAnExplicitBackAction() {
    var wentBack = false
    composeTestRule.setContent {
      WhiteboardTheme {
        ProfileSetupScreen(
          existing = com.ditto.whiteboard.data.ProfileSettings("Ada", WHITEBOARD_COLORS[1]),
          editing = true,
          onSave = { _, _ -> },
          onBack = { wentBack = true },
        )
      }
    }

    composeTestRule.onNodeWithContentDescription("Back").performClick()
    composeTestRule.runOnIdle { assertTrue(wentBack) }
  }

  @Test
  fun clearBoardRequiresConfirmation() {
    var cleared = false
    composeTestRule.setContent {
      WhiteboardTheme {
        BoardScreen(
          state = BoardUiState(
            diagnostics = TransportDiagnostics(editingReady = true),
          ),
          onSelectTool = {},
          onSelectColor = {},
          onPreview = { _, _ -> },
          onCommit = { _, _, _ -> },
          onClear = { cleared = true },
          onEditProfile = {},
          onTroubleshooting = {},
        )
      }
    }

    composeTestRule.onNodeWithContentDescription("Clear board").performClick()
    composeTestRule.onNodeWithText("Clear the shared board?").assertIsDisplayed()
    composeTestRule.onNodeWithText("Clear board").performClick()
    composeTestRule.runOnIdle { assertTrue(cleared) }
  }

  @Test
  fun boardExposesViewportControlsAndCanReturnToFit() {
    composeTestRule.setContent {
      WhiteboardTheme {
        BoardScreen(
          state = BoardUiState(),
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

    composeTestRule.onNodeWithContentDescription("Zoom out").assertIsDisplayed()
    composeTestRule.onNodeWithContentDescription("Zoom in").assertIsDisplayed()
    composeTestRule.onNodeWithText("Fill").assertIsDisplayed()
    composeTestRule.onNodeWithText("Fit").performClick()
    composeTestRule.onNodeWithText("100%").assertIsDisplayed()
  }

  @Test
  fun connectedPeopleShowsNamesAndAssignedColors() {
    val state = BoardUiState(
      board = BoardState(
        profiles = kotlinx.collections.immutable.persistentMapOf(
          "local" to UserProfile("local", "Ada", WHITEBOARD_COLORS[1]),
          "remote" to UserProfile("remote", "Grace", WHITEBOARD_COLORS[2]),
        ),
      ),
      diagnostics = TransportDiagnostics(
        localPeerKey = "local",
        running = true,
        peers = mapOf("remote" to PeerDiagnostics(peerKey = "remote", transports = setOf("LAN"))),
      ),
    )
    composeTestRule.setContent {
      WhiteboardTheme {
        BoardScreen(
          state = state,
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

    composeTestRule.onNodeWithContentDescription("Connected people, 2").performClick()
    composeTestRule.onNodeWithText("People on this board").assertIsDisplayed()
    composeTestRule.onNodeWithText("Ada").assertIsDisplayed()
    composeTestRule.onNodeWithText("Grace").fetchSemanticsNode()
    composeTestRule.onNodeWithText("You • Blue").assertIsDisplayed()
    composeTestRule.onNodeWithContentDescription("Green assigned color").fetchSemanticsNode()
  }

  @Test
  @Config(sdk = [35], qualifiers = "w900dp-h500dp")
  fun shortWideLayoutKeepsEveryColorReachableInOverflow() {
    composeTestRule.setContent {
      WhiteboardTheme {
        BoardScreen(
          state = BoardUiState(),
          onSelectTool = {},
          onSelectColor = {},
          onPreview = { _, _ -> },
          onCommit = { _, _, _ -> },
          onClear = {},
          onEditProfile = {},
          onTroubleshooting = {},
          modifier = androidx.compose.ui.Modifier.size(900.dp, 500.dp),
        )
      }
    }

    composeTestRule.onNodeWithContentDescription("More tools and colors").performClick()
    composeTestRule.onNodeWithText("Color 8").fetchSemanticsNode()
  }

  @Test
  @Config(sdk = [35], qualifiers = "w900dp-h800dp")
  fun mediumHeightTabletAlsoKeepsPaletteInReachableOverflow() {
    composeTestRule.setContent {
      WhiteboardTheme {
        BoardScreen(
          state = BoardUiState(),
          onSelectTool = {},
          onSelectColor = {},
          onPreview = { _, _ -> },
          onCommit = { _, _, _ -> },
          onClear = {},
          onEditProfile = {},
          onTroubleshooting = {},
          modifier = androidx.compose.ui.Modifier.size(900.dp, 800.dp),
        )
      }
    }

    composeTestRule.onNodeWithContentDescription("More tools and colors").performClick()
    composeTestRule.onNodeWithText("Color 8").fetchSemanticsNode()
  }
}
