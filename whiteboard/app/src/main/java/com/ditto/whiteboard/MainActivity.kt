package com.ditto.whiteboard

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.net.toUri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ditto.whiteboard.app.WhiteboardApplication
import com.ditto.whiteboard.ui.WhiteboardApp
import com.ditto.whiteboard.ui.WhiteboardViewModel
import com.ditto.whiteboard.ui.WhiteboardViewModelMessages
import com.ditto.whiteboard.ui.theme.WhiteboardTheme
import com.ditto.whiteboard.util.runCatchingException

internal enum class NearbyPermissionPrompt { NotNeeded, Explain, Denied, Dismissed }

class MainActivity : ComponentActivity() {
  private val container by lazy { (application as WhiteboardApplication).container }
  private var permissionPrompt by mutableStateOf(NearbyPermissionPrompt.NotNeeded)
  private val permissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions(),
  ) { result ->
    val allGranted = container.requiredPermissions.all { result[it] == true }
    if (allGranted) {
      container.resolvePermissions(true)
      container.setForeground(true)
    } else {
      container.resolvePermissions(false)
      container.setForeground(false)
    }
    permissionPrompt =
      if (allGranted) NearbyPermissionPrompt.NotNeeded else NearbyPermissionPrompt.Denied
  }
  private val viewModel: WhiteboardViewModel by viewModels {
    WhiteboardViewModel.Factory(
      profiles = container.profileRepository,
      sessionProvider = { container.boardSession },
      messages = WhiteboardViewModelMessages(
        sessionStartFailed = getString(R.string.whiteboard_start_failed),
        profileSessionUpdateFailed = getString(R.string.profile_session_update_failed),
        profileSaveFailed = getString(R.string.profile_save_failed),
      ),
    )
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      window.isNavigationBarContrastEnforced = false
    }
    val allGranted = container.requiredPermissions.all(::hasPermission)
    permissionPrompt = savedInstanceState
      ?.getString(PERMISSION_PROMPT_STATE)
      ?.let { runCatchingException { NearbyPermissionPrompt.valueOf(it) }.getOrNull() }
      ?: if (allGranted) NearbyPermissionPrompt.NotNeeded else NearbyPermissionPrompt.Explain
    if (allGranted) {
      container.resolvePermissions(true)
    } else if (permissionPrompt == NearbyPermissionPrompt.Dismissed) {
      container.resolvePermissions(false)
    }
    setContent {
      WhiteboardTheme {
        WhiteboardApp(
          viewModel = viewModel,
          permissionPrompt = permissionPrompt,
          onRequestNearbyPermissions = {
            permissionLauncher.launch(container.requiredPermissions.toTypedArray())
          },
          onUseLocalPreview = {
            permissionPrompt = chooseLocalPreview(
              resolvePermissions = container::resolvePermissions,
              setForeground = container::setForeground,
            )
          },
          onOpenAppSettings = {
            startActivity(
              Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                "package:$packageName".toUri(),
              ),
            )
          },
        )
      }
    }
  }

  override fun onResume() {
    super.onResume()
    val allGranted = container.requiredPermissions.all(::hasPermission)
    if (permissionPrompt != NearbyPermissionPrompt.Dismissed && allGranted) {
      container.resolvePermissions(true)
      container.setForeground(true)
      permissionPrompt = NearbyPermissionPrompt.NotNeeded
    } else if (permissionPrompt != NearbyPermissionPrompt.Dismissed && !allGranted) {
      // Permissions can be revoked in Settings or by Android auto-reset while this activity is
      // away. Pause nearby work immediately and return to the rationale instead of foregrounding
      // Ditto with permissions it no longer owns.
      container.resolvePermissions(false)
      container.setForeground(false)
      permissionPrompt = resumedPermissionPrompt(permissionPrompt, allGranted = false)
    }
  }

  override fun onStart() {
    super.onStart()
    container.setForeground(
      shouldForegroundSession(
        permissionPrompt = permissionPrompt,
        allGranted = container.requiredPermissions.all(::hasPermission),
      ),
    )
  }

  override fun onStop() {
    // A configuration change keeps the process visible; tearing down every stream on rotation,
    // fold, or locale change creates avoidable radio/CPU churn and interrupts reconciliation.
    if (!isChangingConfigurations) container.setForeground(false)
    super.onStop()
  }

  override fun onSaveInstanceState(outState: Bundle) {
    outState.putString(PERMISSION_PROMPT_STATE, permissionPrompt.name)
    super.onSaveInstanceState(outState)
  }

  private fun hasPermission(permission: String): Boolean =
    androidx.core.content.ContextCompat.checkSelfPermission(this, permission) ==
      android.content.pm.PackageManager.PERMISSION_GRANTED

  private companion object {
    const val PERMISSION_PROMPT_STATE = "nearby_permission_prompt"
  }
}

internal fun resumedPermissionPrompt(
  current: NearbyPermissionPrompt,
  allGranted: Boolean,
): NearbyPermissionPrompt = when {
  allGranted -> NearbyPermissionPrompt.NotNeeded
  current == NearbyPermissionPrompt.Dismissed -> NearbyPermissionPrompt.Dismissed
  current == NearbyPermissionPrompt.Denied -> NearbyPermissionPrompt.Denied
  else -> NearbyPermissionPrompt.Explain
}

internal fun chooseLocalPreview(
  resolvePermissions: (Boolean) -> Unit,
  setForeground: (Boolean) -> Unit,
): NearbyPermissionPrompt {
  resolvePermissions(false)
  // onResume pauses the container while the permission explanation is visible. Local preview is
  // an explicit choice to continue in this same resumed activity, so foreground it immediately.
  setForeground(true)
  return NearbyPermissionPrompt.Dismissed
}

internal fun shouldForegroundSession(
  permissionPrompt: NearbyPermissionPrompt,
  allGranted: Boolean,
): Boolean = allGranted || permissionPrompt == NearbyPermissionPrompt.Dismissed
