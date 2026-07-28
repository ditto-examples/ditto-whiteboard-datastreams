package com.ditto.whiteboard.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Brand roots: ink blue, teal accent, amber tertiary. The full schemes below derive container and
// "on" roles from these so every Material 3 surface the app touches is themed (no baseline-purple
// fallback for tertiaryContainer / surfaceContainer / secondaryContainer, etc.).

private val LightColors = lightColorScheme(
  primary = Color(0xFF274060),
  onPrimary = Color(0xFFFFFFFF),
  primaryContainer = Color(0xFFD6E3F5),
  onPrimaryContainer = Color(0xFF0A1B33),
  secondary = Color(0xFF006D77),
  onSecondary = Color(0xFFFFFFFF),
  secondaryContainer = Color(0xFFB6ECF0),
  onSecondaryContainer = Color(0xFF00363B),
  tertiary = Color(0xFFB54708),
  onTertiary = Color(0xFFFFFFFF),
  tertiaryContainer = Color(0xFFFFDCC2),
  onTertiaryContainer = Color(0xFF3A1600),
  background = Color(0xFFF8F7F2),
  onBackground = Color(0xFF1A1C1E),
  surface = Color(0xFFFFFEFA),
  onSurface = Color(0xFF1A1C1E),
  surfaceVariant = Color(0xFFE8EBE7),
  onSurfaceVariant = Color(0xFF43474B),
  surfaceContainer = Color(0xFFEDEFEA),
  surfaceContainerHigh = Color(0xFFE7E9E4),
  outline = Color(0xFF74777C),
  outlineVariant = Color(0xFFC3C7C4),
  error = Color(0xFFBA1A1A),
  onError = Color(0xFFFFFFFF),
  errorContainer = Color(0xFFFFDAD6),
  onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
  primary = Color(0xFFAFC6E9),
  onPrimary = Color(0xFF102844),
  primaryContainer = Color(0xFF14304F),
  onPrimaryContainer = Color(0xFFD6E3F5),
  secondary = Color(0xFF7DD8DD),
  onSecondary = Color(0xFF00363B),
  secondaryContainer = Color(0xFF004F56),
  onSecondaryContainer = Color(0xFFB6ECF0),
  tertiary = Color(0xFFFFB77E),
  onTertiary = Color(0xFF522300),
  tertiaryContainer = Color(0xFF6E3600),
  onTertiaryContainer = Color(0xFFFFDCC2),
  background = Color(0xFF111416),
  onBackground = Color(0xFFE2E2E6),
  surface = Color(0xFF191C1E),
  onSurface = Color(0xFFE2E2E6),
  surfaceVariant = Color(0xFF303437),
  onSurfaceVariant = Color(0xFFC3C7CB),
  surfaceContainer = Color(0xFF1E2224),
  surfaceContainerHigh = Color(0xFF282C2E),
  outline = Color(0xFF8D9195),
  outlineVariant = Color(0xFF43474B),
  error = Color(0xFFFFB4AB),
  onError = Color(0xFF690005),
  errorContainer = Color(0xFF93000A),
  onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun WhiteboardTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}
