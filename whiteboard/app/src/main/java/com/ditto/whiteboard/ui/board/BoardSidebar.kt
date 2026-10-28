package com.ditto.whiteboard.ui.board

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Troubleshoot
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ditto.whiteboard.R
import com.ditto.whiteboard.data.ProfileSettings
import com.ditto.whiteboard.ui.BoardUiState
import com.ditto.whiteboard.ui.profile.ProfileSetupScreen
import com.ditto.whiteboard.ui.troubleshooting.TroubleshootingScreen
import com.ditto.whiteboard.ui.troubleshooting.presencegraph.PresenceGraphUiState

enum class SidebarSection { People, Profile, Troubleshooting }

private val SidebarSection.icon: ImageVector
  get() = when (this) {
    SidebarSection.People -> Icons.Default.Groups
    SidebarSection.Profile -> Icons.Default.Person
    SidebarSection.Troubleshooting -> Icons.Default.Troubleshoot
  }

private val SidebarSection.labelRes: Int
  get() = when (this) {
    SidebarSection.People -> R.string.sidebar_people
    SidebarSection.Profile -> R.string.sidebar_profile
    SidebarSection.Troubleshooting -> R.string.sidebar_troubleshooting
  }

/**
 * Unified people / profile / troubleshooting panel. The segmented control swaps the content in
 * place without dismissing; the caller owns the container (side pane Surface or bottom sheet).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardSidebarPanel(
  state: BoardUiState,
  section: SidebarSection,
  onSectionChange: (SidebarSection) -> Unit,
  onDismiss: () -> Unit,
  onSaveProfile: (String, Int) -> Unit,
  profile: ProfileSettings?,
  profileErrorMessage: String?,
  presenceGraphState: PresenceGraphUiState,
  modifier: Modifier = Modifier,
) {
  Column(modifier.fillMaxWidth()) {
    SingleChoiceSegmentedButtonRow(
      Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
      SidebarSection.entries.forEachIndexed { index, entry ->
        SegmentedButton(
          selected = entry == section,
          onClick = { onSectionChange(entry) },
          shape = SegmentedButtonDefaults.itemShape(index, SidebarSection.entries.size),
        ) {
          Icon(entry.icon, contentDescription = stringResource(entry.labelRes))
        }
      }
    }
    // Weighted so the section fills the space left by the segmented row with a bounded
    // viewport: embedded sections use fillMaxSize/weighted children, and in a bottom sheet
    // that would otherwise be measured against the full sheet height and clipped at the
    // bottom instead of scrolling.
    Box(Modifier.weight(1f)) {
      when (section) {
        SidebarSection.People -> ConnectedPeoplePane(
          state = state,
          onClose = onDismiss,
          framed = false,
        )
        SidebarSection.Profile -> ProfileSetupScreen(
          existing = profile,
          editing = true,
          onSave = onSaveProfile,
          errorMessage = profileErrorMessage,
          embedded = true,
        )
        SidebarSection.Troubleshooting -> TroubleshootingScreen(
          diagnostics = state.diagnostics,
          presenceGraphState = presenceGraphState,
          onBack = onDismiss,
          embedded = true,
        )
      }
    }
  }
}
