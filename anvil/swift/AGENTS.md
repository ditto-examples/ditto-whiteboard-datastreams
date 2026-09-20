# AGENTS.md — swift/

Guidance for AI agents working in this Swift package.

## What this is

Swift/SwiftUI port of the Anvil design system (Ditto branding). The web source
of truth is `../src/theme-palette.css` (OKLCH palette, 4 tiers) and
`../src/theme.css` (semantic tokens). Do not hand-tune color values here.

## Package layout

- `Anvil/Sources/Anvil/Generated/AnvilPalette.swift` — **GENERATED** by
  `node ../scripts/generate-swift-tokens.mjs` (never edit by hand). Four
  primitive tiers (light/dark/light-HC/dark-HC).
- `Anvil/Sources/Anvil/AnvilSemanticColors.swift` — hand-mapped from
  `theme.css`; keep it faithful and in sync with the Android
  (`AnvilSemanticColors.kt`), Flutter (`anvil_semantic_colors.dart`) and RN
  (`semantic.ts`) ports.
- `Anvil/Sources/Anvil/DittoTheme.swift` — `DittoTheme { ... }` wrapper view
  and the `dittoColors` / `dittoIsDark` environment keys. HC follows the
  system Increase Contrast setting (`colorSchemeContrast`) unless overridden.
- `Anvil/Sources/Anvil/DittoComponents.swift` — v1 set: `AnvilButton`,
  `AnvilBadge`, `AnvilCard`, `AnvilInput`. Add components sparingly; SwiftUI
  accessibility defaults do more work than RN primitives, still verify VO.

## Rules

1. Color changes → edit CSS in the web package, re-run **all four** generators
   (android/flutter/react-native/swift), never edit generated code.
2. Semantic-token changes: update `AnvilSemanticColors.swift`, mirror to the
   other three ports' semantic layers, then run `swift test` here and the
   other ports' contrast guards.
3. Raw palette colors (`AnvilLightPalette.citrus600` etc.) must not appear in
   app code — apps use `@Environment(\.dittoColors)`.
4. Kairos/Aeonik are commercial fonts — never commit them. `DittoFonts` holds
   name constants only ("Inter", "IBMPlexMono"); the consuming app registers
   the font files itself.
5. No dependencies in Package.swift. Anvil-Swift is pure SwiftUI.

## Build & verify

```bash
swift test --package-path swift/Anvil          # WCAG contrast guards (6 suites)
node scripts/generate-swift-tokens.mjs         # regenerate tokens (repo root)
```

Sample app: `swift/Catalog/` — an SPM executable SwiftUI app;
`swift run --package-path swift/Catalog` launches a macOS window with the
tier switcher.
