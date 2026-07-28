package com.ditto.whiteboard.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
  fun clearBoardRequiresConfirmation() {
    var cleared = false
    composeTestRule.setContent {
      WhiteboardTheme {
        BoardScreen(
          state = BoardUiState(),
          onSelectTool = {},
          onSelectColor = {},
          onPreview = {},
          onCommit = { _, _ -> },
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
          onPreview = {},
          onCommit = { _, _ -> },
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
        profiles = mapOf(
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
          onPreview = {},
          onCommit = { _, _ -> },
          onClear = {},
          onEditProfile = {},
          onTroubleshooting = {},
        )
      }
    }

    composeTestRule.onNodeWithContentDescription("Connected people, 2").performClick()
    composeTestRule.onNodeWithText("People on this board").assertIsDisplayed()
    composeTestRule.onNodeWithText("Ada").assertIsDisplayed()
    composeTestRule.onNodeWithText("Grace").assertIsDisplayed()
    composeTestRule.onNodeWithText("You • Blue").assertIsDisplayed()
    composeTestRule.onNodeWithContentDescription("Green assigned color").assertIsDisplayed()
  }
}
