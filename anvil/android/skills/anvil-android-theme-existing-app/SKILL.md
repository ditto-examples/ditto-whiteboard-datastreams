---
name: anvil-android-theme-existing-app
description: Re-theme an existing Android Jetpack Compose or Compose Multiplatform app with Ditto's Anvil design system (Ditto colors, typography, light/dark/high-contrast). Use when migrating an app's existing MaterialTheme/ColorScheme to Ditto branding.
---

# Re-theme an existing app with Anvil

Goal: replace the app's existing theming with Ditto branding while keeping all
component behavior. Two artifacts — pick the one matching the app's Compose stack:

| App stack | Artifact |
|---|---|
| androidx.compose.material3 (any version ≥1.3) | `live.ditto.anvil:anvil-material3` |
| CMP `org.jetbrains.compose.material:material3` | `live.ditto.anvil:anvil-cmp` |

Never mix both stacks in one module.

## Migration steps

### 1. Inventory current theming

Find and list: the app's `ColorScheme`/theme composable (often `ui/theme/Theme.kt`),
any hardcoded `Color(0x...)` / `Color.Red`-style usages in screens, and custom
`Typography`/`Shapes`. These are the things being replaced.

### 2. Swap the dependency and the theme wrapper

Add:

```kotlin
implementation("live.ditto.anvil:anvil-material3:0.1.0-SNAPSHOT")   // or :anvil-cmp
```

Replace the app's theme composable call site at the root (usually in `MainActivity.setContent`
or the CMP `fun main()` / shared `App()`):

```kotlin
DittoTheme(mode = DittoThemeMode.System) { AppContent() }
```

Then delete (or thin out) the old theme composable so its `MaterialTheme` call
doesn't shadow the Ditto one — a nested `MaterialTheme(colorScheme = ...)` will
override Ditto colors below it.

### 3. Remove hardcoded colors

For each hardcoded color, choose the intent, not the hue:

| Hardcoded intent | Replace with |
|---|---|
| Primary brand fill | `MaterialTheme.colorScheme.primary` (+ `onPrimary`) |
| Accent/promo | `MaterialTheme.colorScheme.tertiary` |
| Errors | `MaterialTheme.colorScheme.error` / `errorContainer` |
| Body/caption text | `onSurface` / `onSurfaceVariant` |
| Surfaces/backgrounds | `surface`, `surfaceContainer*`, `background` |
| Semantic status (info/success/warning/critical/promo) | `DittoColors.current.fill{Info,Success,Warning,Critical,Promo}[Secondary]` |
| Borders/dividers | `DittoColors.current.borderNormal` or `outlineVariant` |
| Code text | `DittoColors.current.code*` |

### 4. Typography

DittoTheme installs Inter for title/body/label and handles display/headline via
`brandFontFamily` (Kairos, not bundled). Remove the app's custom `Typography`
or reduce it to overrides that still derive from Ditto defaults. For monospace
use `dittoMonoFontFamily()` (CMP) or `DittoMonoFontFamily` (Android).

### 5. Dark mode cleanups

- Delete any `if (isSystemInDarkTheme())` forks around colors — the theme handles it.
- If the app previously forced light-only, remove the override and let
  `DittoThemeMode.System` apply; check the four tiers (light, dark, HC x2) —
  pass `highContrast = true` temporarily to audit HC rendering.
- `dynamicColor` stays off by default; if the old app had dynamic color on and
  users expect it, pass `dynamicColor = true` explicitly (Android artifact only,
  Android 12+).

### 6. Verify

1. Build: `./gradlew :<app-module>:assembleDebug`.
2. Check key screens in light AND dark mode (toggle via system) — cards, text
   fields, dialogs, pickers, navigation surfaces are the usual regressions.
3. Cross-check against the catalog apps (`android/catalog`,
   `android/catalog-expressive` in this monorepo) for expected rendering.
4. If the repo has screenshot tests, regenerate goldens and review diffs
   deliberately — they should ONLY change via theming.

## Common pitfalls

- Nested `MaterialTheme {}` calls or `CompositionLocalProvider(LocalColors...)` overrides re-tinting subtrees.
- Hardcoded `Color.White` text on surfaces that are no longer light.
- Custom components reading old app color objects instead of `MaterialTheme.colorScheme` / `DittoColors.current`.
- CMP project accidentally depending on both `material3` artifacts.
