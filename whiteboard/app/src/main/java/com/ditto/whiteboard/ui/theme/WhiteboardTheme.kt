package com.ditto.whiteboard.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import live.ditto.anvil.material3.DittoTheme
import live.ditto.anvil.material3.DittoThemeMode

@Composable
fun WhiteboardTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
  DittoTheme(
    mode = if (darkTheme) DittoThemeMode.Dark else DittoThemeMode.Light,
    content = content,
  )
}
