package com.ditto.whiteboard.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import live.ditto.anvil.material3.DittoTheme
import live.ditto.anvil.material3.DittoThemeMode
import live.ditto.anvil.tokens.AnvilLightPalette

@Composable
fun WhiteboardTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
  DittoTheme(
    mode = if (darkTheme) DittoThemeMode.Dark else DittoThemeMode.Light,
    content = content,
  )
}

/** Ditto's stable citrus-and-black on-state for switches in every theme. */
@Composable
fun dittoSwitchColors(): SwitchColors = SwitchDefaults.colors(
  checkedThumbColor = AnvilLightPalette.neutral950,
  checkedTrackColor = AnvilLightPalette.citrus600,
  checkedBorderColor = AnvilLightPalette.citrus600,
)
