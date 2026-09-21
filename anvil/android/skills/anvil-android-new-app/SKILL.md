---
name: anvil-android-new-app
description: Create a new Android or Compose Multiplatform app themed with Ditto's Anvil design system (colors, Inter/typography, light/dark/high-contrast). Use when scaffolding a new Ditto-branded Kotlin app.
---

# New Ditto-branded app with Anvil

Anvil for Android packages Ditto's design tokens as a Material 3 theme.
Two artifacts exist — pick exactly one per app:

| App type | Artifact | Material library |
|---|---|---|
| Android-only (Jetpack Compose, latest M3 / Expressive) | `live.ditto.anvil:anvil-material3` | `androidx.compose.material3` (≥1.4 stable) |
| Compose Multiplatform (Android + desktop + iOS) | `live.ditto.anvil:anvil-cmp` | CMP `org.jetbrains.compose.material:material3` |

Never put both `androidx.compose.material3` **and** CMP `material3` on the
same classpath — they share the `androidx.compose.material3` package and the
build will break.

## Steps

### 1. Add the dependency

Android app module `build.gradle.kts`:

```kotlin
dependencies {
    implementation("live.ditto.anvil:anvil-material3:0.1.0-SNAPSHOT")
}
```

CMP shared module:

```kotlin
kotlin {
    sourceSets.commonMain.dependencies {
        implementation("live.ditto.anvil:anvil-cmp:0.1.0-SNAPSHOT")
    }
}
```

(If publishing is not configured yet, consume the library from this monorepo:
`implementation(project(":anvil-material3"))` / `project(":anvil-cmp")`.)

### 2. Wrap the app in DittoTheme

Android (`live.ditto.anvil.material3.DittoTheme`):

```kotlin
setContent {
    DittoTheme {   // follows system light/dark
        NavHostOrAppRoot()
    }
}
```

CMP (`live.ditto.anvil.DittoTheme`) — same call, from `commonMain`.

Options:
- `mode = DittoThemeMode.System | Light | Dark`
- `highContrast = true` — Anvil's high-contrast tiers (stronger borders/text/focus)
- `brandFontFamily` — Kairos Sans for display/headline styles (not bundled; commercial license required)
- `dynamicColor = true` (Android artifact only) — wallpaper colors on Android 12+. Keep off for brand fidelity.

### 3. Write UI against MaterialTheme + DittoColors

- Always use `MaterialTheme.colorScheme.*` / `MaterialTheme.typography.*` — never hardcode hex values or raw palette colors.
- For brand semantics without an M3 role, use `DittoColors.current` (e.g. `fillSuccessSecondary` bg + `fillSuccess` text, `borderWarning`, `codeKeyword`).
- Monospace: `dittoMonoFontFamily()` (CMP) / `DittoMonoFontFamily` (Android) — IBM Plex Mono is bundled. Inter is bundled and applied automatically.
- Light/dark is automatic; do not branch on `isSystemInDarkTheme()` in app code unless building custom themed components — override via the `mode` parameter instead.

### 4. Verify visually

The reference implementations are the catalog apps in this monorepo:
- `android/catalog` (CMP — component gallery) — `./gradlew :catalog:installDebug`
- `android/catalog-expressive` (M3 Expressive gallery) — `./gradlew :catalog-expressive:installDebug`

If a component renders differently from the catalog, look for hardcoded colors or nested themes in the app code.

## Accessibility notes

- Body text on surfaces is guaranteed ≥ 4.5:1 in all tiers (enforced by tests).
- Black-on-citrus `primary` fills are ~4:1 — use large/bold text on `primary` buttons; use `primaryContainer` for small text.
- `foregroundAccent` is decorative/large-text only (~2.8:1 in light mode).
