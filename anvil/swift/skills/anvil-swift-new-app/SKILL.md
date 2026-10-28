---
name: anvil-swift-new-app
description: Create a new SwiftUI app themed with Ditto's Anvil design system (Ditto colors, light/dark, high-contrast tiers). Use when scaffolding a new Ditto-branded iOS or macOS app.
---

# New Ditto-branded app with Anvil (SwiftUI)

Anvil for Swift is an SPM package (`Anvil`) packaging Ditto's design tokens as
SwiftUI environment values + a minimal component set. Zero dependencies, pure
SwiftUI, iOS 16+ / macOS 13+.

## Steps

### 1. Add the package

In Xcode: File → Add Package Dependencies → local path `swift/Anvil`. Or in
`Package.swift`:

```swift
.package(path: "../anvil/swift/Anvil")
```

### 2. Wrap the app root in DittoTheme

```swift
import Anvil
import SwiftUI

@main
struct MyApp: App {
    var body: some Scene {
        WindowGroup {
            DittoTheme {          // follows system light/dark
                AppRoot()         // HC follows Increase Contrast setting
            }
        }
    }
}
```

Options: `mode: .system | .light | .dark`; `highContrast: Bool?` (nil follows
the system accessibility setting; pass true/false to override).

### 3. Write UI against the semantic colors

- Read colors with `@Environment(\.dittoColors)` — e.g. `colors.fillSuccess`,
  `colors.borderWarning`, `colors.codeKeyword`. Never hardcode hex values or
  use raw palette colors (`AnvilLightPalette.*` must not appear in app code).
- Use the themed components for UI chrome: `AnvilButton("Save") {}`,
  `AnvilBadge("Synced", status: .success)`, `AnvilCard { ... }`,
  `AnvilInput(label:placeholder:error:text:)`. Extend with view modifiers,
  don't re-color.
- Monospace: `Font.dittoCode()` (IBM Plex Mono — see Fonts below).

### 4. Fonts

Fonts are **not bundled**. To get Inter / IBM Plex Mono:

1. Add the TTFs to the app target (or Assets) and list them in Info.plist
   `UIAppFonts` (iOS) / `ATSApplicationFontsPath` (macOS).
2. The family names are `DittoFonts.sans` ("Inter") and `DittoFonts.mono`
   ("IBMPlexMono") — the components reference exactly these.
3. Without the fonts, everything renders in the system font — acceptable for
   internal tools, not for branded surfaces.

### 5. Verify visually

The reference implementation is the catalog in this monorepo:

```bash
swift run --package-path swift/Catalog   # macOS window with tier switcher
```

Flip System/Light/Dark and the High contrast toggle. If a view renders
differently, look for hardcoded colors or a `.environment` override shadowing
the Ditto environment keys.

## Accessibility notes

- Body text on surfaces is ≥ 4.5:1 in all 4 tiers (XCTest guards in
  `swift/Anvil/Tests`).
- Black-on-citrus brand fills are ~4:1 in dark mode — `AnvilButton` sets the
  correct on-brand content color automatically; for custom primary surfaces
  use `colors.foregroundOnBrandPrimary`.
- `foregroundAccent` is decorative/large-text only (~2.8:1 in light mode).
