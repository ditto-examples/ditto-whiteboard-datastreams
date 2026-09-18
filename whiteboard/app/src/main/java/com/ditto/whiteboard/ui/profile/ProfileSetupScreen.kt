package com.ditto.whiteboard.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.ditto.whiteboard.data.ProfileSettings
import com.ditto.whiteboard.R
import com.ditto.whiteboard.ui.WHITEBOARD_COLORS

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSetupScreen(
  existing: ProfileSettings?,
  editing: Boolean,
  onSave: (String, Int) -> Unit,
  modifier: Modifier = Modifier,
  errorMessage: String? = null,
  onBack: () -> Unit = {},
) {
  var name by rememberSaveable(existing?.displayName) { mutableStateOf(existing?.displayName.orEmpty()) }
  var color by rememberSaveable(existing?.colorArgb) {
    mutableIntStateOf(existing?.colorArgb ?: WHITEBOARD_COLORS[1])
  }
  var attemptedSave by rememberSaveable { mutableStateOf(false) }
  val valid = name.trim().length in 1..24 && name.none(Char::isISOControl)

  Scaffold(
    modifier = modifier.fillMaxSize(),
    topBar = {
      TopAppBar(
        title = {
          Text(
            stringResource(
              if (editing) R.string.profile_edit_title else R.string.profile_welcome_title,
            ),
          )
        },
        navigationIcon = {
          if (editing) {
            IconButton(onClick = onBack) {
              Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
              )
            }
          }
        },
      )
    },
  ) { padding ->
    // Scrollable + IME-aware so the action button stays reachable on short/landscape screens with
    // the keyboard open, instead of being pushed off a fixed, centered column.
    Column(
      modifier = Modifier
        .padding(padding)
        .fillMaxSize()
        .imePadding()
        .verticalScroll(rememberScrollState())
        .navigationBarsPadding()
        .padding(horizontal = 24.dp, vertical = 16.dp),
      horizontalAlignment = Alignment.Start,
    ) {
      Text(stringResource(R.string.profile_privacy_explanation), style = MaterialTheme.typography.bodyLarge)
      errorMessage?.let { message ->
        Spacer(Modifier.height(12.dp))
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
      }
      Spacer(Modifier.height(24.dp))
      OutlinedTextField(
        value = name,
        onValueChange = { value ->
          name = value.filterNot(Char::isISOControl).take(24)
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.display_name)) },
        supportingText = { Text(stringResource(R.string.display_name_support)) },
        isError = attemptedSave && !valid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (valid) onSave(name.trim(), color) }),
      )
      Spacer(Modifier.height(20.dp))
      Text(stringResource(R.string.default_drawing_color), style = MaterialTheme.typography.titleMedium)
      Spacer(Modifier.height(12.dp))
      FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WHITEBOARD_COLORS.forEachIndexed { index, option ->
          val selected = color == option
          val description = stringResource(
            if (selected) R.string.color_number_selected else R.string.color_number,
            index + 1,
          )
          Spacer(
            Modifier
              .size(48.dp)
              .clip(CircleShape)
              .background(Color(option))
              .border(if (selected) 4.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
              .clickable { color = option }
              .semantics {
                role = Role.RadioButton
                this.selected = selected
                contentDescription = description
              },
          )
        }
      }
      Spacer(Modifier.height(32.dp))
      Button(
        onClick = { attemptedSave = true; if (valid) onSave(name.trim(), color) },
        enabled = valid,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          stringResource(if (editing) R.string.action_save_profile else R.string.action_join_board),
        )
      }
    }
  }
}
