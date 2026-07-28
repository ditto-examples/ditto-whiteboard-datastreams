package com.ditto.whiteboard.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.ditto.whiteboard.ui.board.BoardScreen
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
fun WhiteboardApp(viewModel: WhiteboardViewModel) {
  val profileState by viewModel.profiles.state.collectAsStateWithLifecycle()
  val boardState by viewModel.uiState.collectAsStateWithLifecycle()
  if (!profileState.loaded) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    return
  }

  if (profileState.profile == null) {
    ProfileSetupScreen(
      existing = null,
      editing = false,
      onSave = { name, color -> viewModel.saveProfile(name, color, onSaved = {}) },
    )
    return
  }

  val profile = profileState.profile ?: return
  LaunchedEffect(profile) { viewModel.startSession(profile.displayName, profile.colorArgb) }

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
          onSave = { name, color ->
            viewModel.saveProfile(name, color) {
              if (route.editing) {
                backStack.removeLastOrNull()
              }
            }
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
}
