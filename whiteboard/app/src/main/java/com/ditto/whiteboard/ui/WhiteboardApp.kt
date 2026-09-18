package com.ditto.whiteboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation3.SupportingPaneSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberSupportingPaneSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.ditto.whiteboard.ui.board.BoardScreen
import com.ditto.whiteboard.NearbyPermissionPrompt
import com.ditto.whiteboard.R
import com.ditto.whiteboard.ui.profile.ProfileSetupScreen
import com.ditto.whiteboard.ui.troubleshooting.TroubleshootingScreen
import kotlinx.serialization.Serializable

@Serializable
data class ProfileSetupRoute(val editing: Boolean = false) : NavKey

@Serializable
data object BoardRoute : NavKey

@Serializable
data object TroubleshootingRoute : NavKey

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun WhiteboardApp(
  viewModel: WhiteboardViewModel,
  permissionPrompt: NearbyPermissionPrompt = NearbyPermissionPrompt.NotNeeded,
  onRequestNearbyPermissions: () -> Unit = {},
  onUseLocalPreview: () -> Unit = {},
  onOpenAppSettings: () -> Unit = {},
) {
  val profileState by viewModel.profiles.state.collectAsStateWithLifecycle()
  val boardState by viewModel.uiState.collectAsStateWithLifecycle()
  if (profileState.failure != null) {
    ProfileLoadError(onRetry = viewModel.profiles::retryLoad)
    return
  }
  if (!profileState.loaded) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    return
  }

  if (profileState.profile == null) {
    ProfileSetupScreen(
      existing = null,
      editing = false,
      errorMessage = boardState.errorMessage,
      onSave = { name, color -> viewModel.saveProfile(name, color, onSaved = {}) },
    )
    return
  }

  val profile = profileState.profile ?: return
  LaunchedEffect(profile, permissionPrompt) {
    if (
      permissionPrompt == NearbyPermissionPrompt.NotNeeded ||
      permissionPrompt == NearbyPermissionPrompt.Dismissed
    ) {
      viewModel.startSession(profile.displayName, profile.colorArgb)
    }
  }

  val initialRoute: NavKey = BoardRoute
  val backStack = rememberNavBackStack(initialRoute)
  val windowAdaptiveInfo = currentWindowAdaptiveInfoV2()
  val directive = remember(windowAdaptiveInfo) {
    calculatePaneScaffoldDirective(windowAdaptiveInfo).copy(
      horizontalPartitionSpacerSize = 0.dp,
      verticalPartitionSpacerSize = 0.dp,
    )
  }
  val supportingPaneStrategy = rememberSupportingPaneSceneStrategy<NavKey>(
    backNavigationBehavior = BackNavigationBehavior.PopUntilCurrentDestinationChange,
    directive = directive,
  )

  fun show(route: NavKey) {
    if (backStack.lastOrNull() != route) backStack.add(route)
  }

  BoxWithConstraints(Modifier.fillMaxSize()) {
    val useSupportingPane = maxWidth >= 840.dp && maxHeight >= 480.dp
    NavDisplay(
      backStack = backStack,
      onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
      sceneStrategies = if (useSupportingPane) listOf(supportingPaneStrategy) else emptyList(),
      entryProvider = entryProvider {
      entry<BoardRoute>(metadata = SupportingPaneSceneStrategy.mainPane()) {
        BoardScreen(
          state = boardState,
          onSelectTool = viewModel::selectTool,
          onSelectColor = viewModel::selectColor,
          onPreview = viewModel::preview,
          onCommit = viewModel::commit,
          onClear = viewModel::clear,
          onEditProfile = { show(ProfileSetupRoute(editing = true)) },
          onTroubleshooting = { show(TroubleshootingRoute) },
        )
      }
      entry<ProfileSetupRoute>(metadata = SupportingPaneSceneStrategy.supportingPane()) { route ->
        ProfileSetupScreen(
          existing = profile,
          editing = route.editing,
          errorMessage = boardState.errorMessage,
          onSave = { name, color ->
            viewModel.saveProfile(name, color) {
              if (route.editing) {
                backStack.removeLastOrNull()
              }
            }
          },
          onBack = {
            if (route.editing) backStack.removeLastOrNull()
          },
        )
      }
      entry<TroubleshootingRoute>(metadata = SupportingPaneSceneStrategy.supportingPane()) {
        TroubleshootingScreen(
          diagnostics = boardState.diagnostics,
          onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
        )
      }
      },
    )
  }

  NearbyPermissionDialog(
    prompt = permissionPrompt,
    onRequest = onRequestNearbyPermissions,
    onUseLocalPreview = onUseLocalPreview,
    onOpenSettings = onOpenAppSettings,
  )
}

@Composable
private fun ProfileLoadError(onRetry: () -> Unit) {
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    androidx.compose.foundation.layout.Column(
      modifier = Modifier.padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(stringResource(R.string.profile_load_failed_title))
      Text(stringResource(R.string.profile_load_failed_explanation))
      Button(onClick = onRetry) { Text(stringResource(R.string.action_retry_profile)) }
    }
  }
}

@Composable
internal fun NearbyPermissionDialog(
  prompt: NearbyPermissionPrompt,
  onRequest: () -> Unit,
  onUseLocalPreview: () -> Unit,
  onOpenSettings: () -> Unit,
) {
  when (prompt) {
    NearbyPermissionPrompt.NotNeeded, NearbyPermissionPrompt.Dismissed -> Unit
    NearbyPermissionPrompt.Explain -> AlertDialog(
      onDismissRequest = onUseLocalPreview,
      title = { Text(stringResource(R.string.permission_nearby_title)) },
      text = {
        Text(stringResource(R.string.permission_nearby_explanation))
      },
      confirmButton = { Button(onClick = onRequest) { Text(stringResource(R.string.action_continue)) } },
      dismissButton = {
        TextButton(onClick = onUseLocalPreview) { Text(stringResource(R.string.action_use_local_preview)) }
      },
    )
    NearbyPermissionPrompt.Denied -> AlertDialog(
      onDismissRequest = onUseLocalPreview,
      title = { Text(stringResource(R.string.permission_denied_title)) },
      text = {
        Text(stringResource(R.string.permission_denied_explanation))
      },
      confirmButton = { Button(onClick = onRequest) { Text(stringResource(R.string.action_retry)) } },
      dismissButton = {
        Row(horizontalArrangement = Arrangement.End) {
          TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.action_open_settings)) }
          TextButton(onClick = onUseLocalPreview) { Text(stringResource(R.string.local_preview)) }
        }
      },
    )
  }
}
