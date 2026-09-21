# Anvil for Android — Ditto design system for Jetpack Compose & Compose Multiplatform

This is the Android/Kotlin port of [Anvil](../README.md), Ditto's React design
system. It packages Ditto's colors, typography, and branding as Material 3
themes so any Jetpack Compose or Compose Multiplatform (CMP) app renders with
the Ditto look in **light mode, dark mode, and both high-contrast tiers**.

> Anvil on the web stays the **single source of truth** for colors:
> `src/theme-palette.css` (OKLCH palette) and `src/theme.css` (semantic tokens)
> are converted to Kotlin by `scripts/generate-android-tokens.mjs`.

## Modules

| Module | What it is | Use it for |
|---|---|---|
| `:anvil-tokens` | KMP library. Generated primitive palettes (4 tiers) + hand-mapped semantic token layer (`AnvilSemanticColors`). | Everything depends on this; usable alone for custom components. |
| `:anvil-cmp` | KMP library. `DittoTheme` built on CMP `material3` (Compose Multiplatform 1.9.3). | CMP apps (Android, desktop, iOS targets). |
| `:anvil-material3` | Android library. `DittoTheme` built on the latest stable `androidx.compose.material3` (1.4.0) + dynamic-color option. | Native Android apps, including Material 3 Expressive. |
| `:catalog` | CMP sample app (Android + desktop). Every M3 component, themed. | Visual reference for the CMP theme. |
| `:catalog-expressive` | Android sample app with M3 Expressive components (runs on `material3:1.5.0-alpha26`). | Visual reference for the latest component set. |

| CMP catalog — light | CMP catalog — dark |
|---|---|
| ![CMP catalog in light mode](docs/screenshots/catalog-light.png) | ![CMP catalog in dark mode](docs/screenshots/catalog-dark.png) |

| Expressive catalog — light | Expressive catalog — dark |
|---|---|
| ![M3 Expressive catalog in light mode](docs/screenshots/catalog-expressive-light.png) | ![M3 Expressive catalog in dark mode](docs/screenshots/catalog-expressive-dark.png) |

> **Do not mix stacks in one app.** CMP `material3` and `androidx.compose.material3` share the
> `androidx.compose.material3` package name — putting both on one classpath breaks the build.
> CMP app → `:anvil-cmp`. Android-only app → `:anvil-material3`.

## Quickstart

### Native Android app (Jetpack Compose, latest Material 3)

```kotlin
// build.gradle.kts (app)
dependencies {
    implementation("live.ditto.anvil:anvil-material3:<version>")
}
```

```kotlin
setContent {
    DittoTheme {               // follows system light/dark by default
        AppRoot()              // every M3 component now uses Ditto colors + Inter
    }
}
```

### Compose Multiplatform app

```kotlin
// shared module
kotlin {
    sourceSets.commonMain.dependencies {
        implementation("live.ditto.anvil:anvil-cmp:<version>")
    }
}
```

```kotlin
DittoTheme(mode = DittoThemeMode.System) { AppRoot() }
```

### `DittoTheme` reference

| Parameter | Type | Default | Meaning |
|---|---|---|---|
| `mode` | `DittoThemeMode` (`System` / `Light` / `Dark`) | `System` | Light/dark. `System` follows the device. |
| `highContrast` | `Boolean` | `false` | Port of Anvil's `light-high-contrast` / `dark-high-contrast` web tiers (stronger borders, text, focus). |
| `brandFontFamily` | `FontFamily?` | `null` | Typeface for `display*`/`headline*` styles. Pass **Kairos Sans** here for the full Ditto look — Kairos is commercial (Monotype) and intentionally *not* bundled; falls back to Inter. |
| `dynamicColor` | `Boolean` (`anvil-material3` only) | `false` | Android 12+ wallpaper dynamic color. Off by default so Ditto branding is never silently replaced. |

### Extended (semantic) colors

Material 3 has no roles for info/success/warning/promo or Anvil's
border/fill/progress/code semantics — they are available via `DittoColors`:

```kotlin
Surface(color = DittoColors.current.fillSuccessSecondary) {
    Text("Everything is synced", color = DittoColors.current.fillSuccess)
}

Text("call GET /docs/find", fontFamily = /* monospace */, color = DittoColors.current.codeKeyword)
```

Key accessors: `fillBrandPrimary`, `fill{Info,Success,Warning,Critical,Promo}[Secondary]`,
`foreground{Normal,Subtle,Accent,Warning,Disabled,...}`, `border{Normal,Strong,...}`,
`ring` / `focusOutline`, `progress[Remaining]`, `code*` colors.

### Fonts

- **Inter** (OFL) — bundled, used for title/body/label.
- **IBM Plex Mono** (OFL) — bundled (`dittoMonoFontFamily()` / `DittoMonoFontFamily`).
- **Kairos Sans** — Ditto's brand display font; commercial, not bundled. Supply via `brandFontFamily` if licensed.

## Color architecture

```
src/theme-palette.css ──┐ (OKLCH, 4 tiers: light / dark / light-HC / dark-HC)
                        ▼  scripts/generate-android-tokens.mjs
  AnvilPalette.kt  (generated: Anvil{Light,Dark,LightHighContrast,DarkHighContrast}Palette
                   + tier-resolved primary50..primary950 citrus aliases)
                        ▼  hand-mapped semantic layer (AnvilSemanticColors.kt)
  AnvilSemanticColors (brand fills, status fills, foregrounds, borders, code…)
                        ▼  role mapping (DittoColorScheme.kt in each theme module)
  Material ColorScheme (light/dark/HC) + Anvil extended colors
```

Design decisions encoded:
- `primary` = Anvil brand fill with **black content** (black-on-citrus is the
  Ditto signature). Body-sized text on primary fills is ~4:1 contrast — keep
  primary-text big/bold or use `primaryContainer` for small text.
- `tertiary` = Anvil "promo" violet; `error` = "critical" red.
- Info/success/warning have no M3 role → extended colors.

## Accessibility

Contrast is guarded at two layers:

- `ContrastTest` (`:anvil-tokens:jvmTest`) — semantic token pairs across all
  four tiers: body text ≥ 4.5:1, brand fills ≥ 3:1 (AA large text/UI).
- `SchemeContrastTest` (`:anvil-cmp:desktopTest`) — the shipped M3
  `ColorScheme` role pairs (containers, filled roles, inverse roles). This is
  where mapping regressions are caught.

Known deviations mirrored from the web: the citrus accent foreground is ~2.8:1
on light backgrounds — reserve `foregroundAccent` for decorative/large text.

**Dark high-contrast fidelity:** the web applies `dark-high-contrast` as a
single class — the plain dark overrides do not cascade in. The Android port
replicates that exactly (surfaces stay at dark-HC defaults, e.g. 2%/4% hover
alphas, 500-step info/promo fills). If the web team revisits that behavior,
change the CSS and regenerate.

## Development

```bash
# regenerate tokens after changing src/theme-palette.css (repo root)
node scripts/generate-android-tokens.mjs

cd android
./gradlew :anvil-tokens:jvmTest          # contrast/unit tests
./gradlew :catalog:compileDebugKotlinAndroid :catalog:compileKotlinDesktop
./gradlew :catalog-expressive:assembleDebug
```

Toolchain: Gradle 9.5.1 (wrapper), AGP 8.13.2, Kotlin 2.2.21, CMP 1.9.3,
composeBom 2025.12.01, minSdk 24 / compileSdk 36 (catalog-expressive: 37).

## Publishing

Coordinates: `live.ditto.anvil:{anvil-tokens,anvil-cmp,anvil-material3}` at
`0.1.0-SNAPSHOT`. Publications are declared; `publishToMavenLocal` works out of
the box. Wire a staging repo (Maven Central via Vanniktech's plugin or an
internal Artifactory) before an external release.
