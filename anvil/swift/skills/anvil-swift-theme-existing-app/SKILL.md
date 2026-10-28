---
name: anvil-swift-theme-existing-app
description: Re-theme an existing SwiftUI app with Ditto's Anvil design system (Ditto colors, light/dark, high-contrast tiers). Use when replacing hardcoded Colors or an ad-hoc theme in a SwiftUI app.
---

# Re-theme an existing SwiftUI app with Anvil

Goal: replace hardcoded colors and ad-hoc theming with Ditto branding while
keeping view behavior. Single artifact: the `Anvil` SPM package — zero new
dependencies.

## Migration steps

### 1. Inventory current theming

Find and list:
- Hardcoded `Color(...)` / `Color(hex:)` / `.red`-style usages
- Any app-level color constants file (`Colors.swift`, `Theme.swift`)
- Custom font helpers / duplicated `Font.custom` calls
- Dark-mode handling (often missing, or `@Environment(\.colorScheme)` forks)

These are all being replaced.

### 2. Add the package and wrap the root

```swift
.package(path: "../anvil/swift/Anvil")
```

At the `WindowGroup` root:

```swift
DittoTheme { AppRoot() }
```

Delete (or thin out) any old theme enum/struct so nothing else shadows the
Ditto environment values — a `.environment(\.dittoColors, …)` injection in
app code will override Ditto below it.

### 3. Replace hardcoded colors

For each hardcoded color, choose the intent, not the hue:

| Hardcoded intent | Replace with |
|---|---|
| Screen/card background | `colors.background` / `colors.surface` |
| Body text | `colors.foregroundNormal` (subtle: `foregroundSubtle`, disabled: `foregroundDisabled`) |
| Brand button fill | `AnvilButton("…")` or `colors.fillBrandPrimary` + `colors.foregroundOnBrandPrimary` |
| Status banners/badges | `AnvilBadge("…", status: .success)` etc. |
| Borders/dividers | `colors.borderNormal`, `borderStrong` |
| Code text | `Font.dittoCode()` + `colors.codeKeyword` |

### 4. Swap common views

- Branded `Button`s with manual styling → `AnvilButton`
- Status pills → `AnvilBadge`
- Card-ish `VStack`s with manual corner/border → `AnvilCard { ... }`
- Labelled `TextField`s → `AnvilInput`
- Leave layout-only views alone — only colors follow the theme.

### 5. Dark mode + high-contrast cleanups

- Delete `@Environment(\.colorScheme)` forks that pick color pairs —
  `dittoColors` already resolve to the right tier.
- Review with Increase Contrast on *and* off: Anvil's HC tiers switch borders
  to opaque + fills to stronger steps; test with `highContrast: false`
  temporarily to see every tier side by side.

### 6. Verify

1. Build on iOS + macOS (package is cross-platform).
2. Walk key screens in light AND dark (cards, text fields, status views).
3. Cross-check against `swift/Catalog` for expected rendering.
4. If the app has snapshot tests (swift-snapshot-testing), re-record and
   review — diffs should be color-only.

## Common pitfalls

- Colors captured in static let / at load before the environment is set —
  Anvil colors come from the environment, read them in `body`.
- Hardcoded `Color.white` text on surfaces that are no longer dark.
- A nested `.environment(\.dittoColors, …)` from a debugging session left in
  app code, shadowing the real tier.
- Forgetting that fonts aren't bundled: Inter falls back silently to the
  system font.
