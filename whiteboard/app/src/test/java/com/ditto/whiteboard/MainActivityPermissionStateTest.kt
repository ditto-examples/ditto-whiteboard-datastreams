package com.ditto.whiteboard

import org.junit.Assert.assertEquals
import org.junit.Test

class MainActivityPermissionStateTest {
  @Test
  fun permissionRevokedWhileAwayReturnsActiveSessionToRationale() {
    assertEquals(
      NearbyPermissionPrompt.Explain,
      resumedPermissionPrompt(NearbyPermissionPrompt.NotNeeded, allGranted = false),
    )
  }

  @Test
  fun explicitLocalPreviewChoiceSurvivesResumeWithoutPermissions() {
    assertEquals(
      NearbyPermissionPrompt.Dismissed,
      resumedPermissionPrompt(NearbyPermissionPrompt.Dismissed, allGranted = false),
    )
  }

  @Test
  fun localPreviewChoiceResolvesPermissionsThenRestoresForegroundImmediately() {
    val transitions = mutableListOf<String>()

    val prompt = chooseLocalPreview(
      resolvePermissions = { transitions += "permissions=$it" },
      setForeground = { transitions += "foreground=$it" },
    )

    assertEquals(NearbyPermissionPrompt.Dismissed, prompt)
    assertEquals(listOf("permissions=false", "foreground=true"), transitions)
  }

  @Test
  fun activityDoesNotForegroundNearbySessionBeforeRevokedPermissionCheck() {
    assertEquals(
      false,
      shouldForegroundSession(NearbyPermissionPrompt.Explain, allGranted = false),
    )
    assertEquals(
      true,
      shouldForegroundSession(NearbyPermissionPrompt.Dismissed, allGranted = false),
    )
    assertEquals(
      true,
      shouldForegroundSession(NearbyPermissionPrompt.NotNeeded, allGranted = true),
    )
  }
}
