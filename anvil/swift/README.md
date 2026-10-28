# Anvil for Swift — Ditto design system for SwiftUI

This is the Swift/SwiftUI port of [Anvil](../../README.md), Ditto's React
design system. It packages Ditto's colors and branding as SwiftUI environment
values + a minimal component set, so any iOS/macOS app renders with the Ditto
look in **light mode, dark mode, and both high-contrast tiers**.

> Anvil on the web stays the **single source of truth** for colors:
> `src/theme-palette.css` (OKLCH palette) is converted to Swift by
> `scripts/generate-swift-tokens.mjs`. Values are verified bit-identical with
> the Flutter port (1,288/1,288 token values matched).

## Packages

| Path | What it is |
|---|---|
| `Anvil/` | SPM library: generated palettes (4 tiers), semantic layer, `DittoTheme` environment contract, minimal components (`AnvilButton`/`AnvilBadge`/`AnvilCard`/`AnvilInput`). Zero dependencies, pure SwiftUI. |
| `Catalog/` | SPM executable sample app (macOS window) with tier switching. |

| Catalog — light | Catalog — dark |
|---|---|
| ![SwiftUI catalog in light mode](docs/screenshots/catalog-light.png) | ![SwiftUI catalog in dark mode](docs/screenshots/catalog-dark.png) |

## Quickstart

Add the package to your Xcode project or `Package.swift`:

```swift
.package(path: "../anvil/swift/Anvil")
```

```swift
import Anvil
import SwiftUI

@main
struct MyApp: App {
    var body: some Scene {
        WindowGroup {
            DittoTheme {              // follows system light/dark;
                AppRoot()             // HC follows Increase Contrast
            }
        }
    }
}
```

### `DittoTheme` reference

| Parameter | Type | Default | Meaning |
|---|---|---|---|
| `mode` | `DittoThemeMode` (`.system` / `.light` / `.dark`) | `.system` | Light/dark. `.system` follows the device. |
| `highContrast` | `Bool?` | `nil` (follow system "Increase Contrast") | Port of Anvil's `light-high-contrast` / `dark-high-contrast` web tiers. |

### Reading colors

```swift
struct SyncPill: View {
    @Environment(\.dittoColors) private var colors

    var body: some View {
        Text("Everything is synced")
            .foregroundStyle(colors.fillSuccess)
            .padding(8)
            .background(colors.fillSuccessSecondary)
            .clipShape(RoundedRectangle(cornerRadius: 8))
    }
}
```

Field names match the Android/Flutter/RN ports exactly: `fillBrandPrimary`,
`fill{Info,Success,Warning,Critical,Promo}[Secondary]`,
`foreground{Normal,Subtle,Accent,Warning,Disabled,...}`,
`border{Normal,Strong,...}`, `ring` / `focusOutline`, `progress[Remaining]`,
`code*` colors.

### Fonts

Fonts are **not bundled**. `DittoFonts.sans` ("Inter") and
`DittoFonts.mono` ("IBMPlexMono") are name constants — register the font
files in your app (Assets catalog or `UIAppFonts`). The system font is used
if they're missing. Kairos Sans (brand display font) is commercial and
intentionally never included.

## Accessibility

- `swift test --package-path Anvil` runs WCAG contrast guards: 6 test suites
  across all four tiers (body text ≥ 4.5:1, brand fills ≥ 3:1, documented
  sub-AA floors matching web Anvil faithfully).
- `DittoTheme(highContrast: nil)` follows the system **Increase Contrast**
  accessibility setting automatically; pass `true`/`false` to override.
- Known deviation (mirrors web + other ports): `foregroundAccent` citrus is
  ~2.8:1 on light backgrounds — decorative/large text only.

## Development

```bash
node scripts/generate-swift-tokens.mjs        # regenerate tokens (repo root)
swift test --package-path swift/Anvil         # contrast guards

swift run --package-path swift/Catalog        # sample app (macOS window)
```

Requires Swift 6 / Xcode 16+; targets iOS 16+ and macOS 13+.

## Publishing

Not published to a package index yet. Mirror the other port coordinates when
releasing (`com.dittolive.anvil:anvil-swift` / SwiftPM git tag).

## AI skill files

`skills/` contains SKILL.md files for coding agents:
- `anvil-swift-new-app` — scaffold a new Ditto-branded SwiftUI app
- `anvil-swift-theme-existing-app` — migrate an existing SwiftUI app to Anvil
