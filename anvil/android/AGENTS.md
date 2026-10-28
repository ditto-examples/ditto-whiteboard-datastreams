# AGENTS.md — android/

Guidance for AI agents working in this Gradle project.

## What this is

Android/Kotlin port of the Anvil design system (Ditto branding). The web
source of truth is `../src/theme-palette.css` (OKLCH palette, 4 tiers) and
`../src/theme.css` (semantic tokens). Do not hand-tune color values here.

## Modules

- `:anvil-tokens` — KMP. `AnvilPalette.kt` is **GENERATED** by
  `node ../scripts/generate-android-tokens.mjs` (never edit by hand).
  `AnvilSemanticColors.kt` is hand-mapped from `theme.css` — keep it faithful.
- `:anvil-cmp` — KMP theme (`live.ditto.anvil.DittoTheme`) on Compose
  Multiplatform material3. Package/class names intentionally overlap with the
  androidx variant.
- `:anvil-material3` — Android library theme (`live.ditto.anvil.material3.DittoTheme`)
  on androidx.compose.material3 (stable). Keep API/mapping in sync with
  `:anvil-cmp`.
- `:catalog` — CMP gallery app. Every component change should be visible here.
- `:catalog-expressive` — Android gallery of M3 Expressive components (app-only
  material3 alpha dependency).

## Rules

1. Never mix CMP material3 and androidx.compose.material3 on the same classpath.
   CMP apps use `:anvil-cmp` only; Android apps use `:anvil-material3` only.
2. Color changes → edit CSS in the web package, re-run the generator, never edit
   generated Kotlin.
3. Semantic-token changes: update `AnvilSemanticColors.kt`, then mirror usage in
   both theme modules, then run `:anvil-tokens:jvmTest` (contrast guards).
4. Raw palette colors (`citrusX00` etc.) must not appear in app code — apps use
   `MaterialTheme.colorScheme` or `DittoColors.current`.
5. Kairos/Aeonik are commercial fonts — never commit them; only Inter and
   IBM Plex Mono (OFL) may be bundled.
6. AGP 8.x with Gradle 9.5.1 wrapper — Gradle ≥9.6 breaks AGP 8; do not upgrade
   the wrapper without upgrading AGP.

## Build & verify

Always run from `android/`:

```bash
./gradlew :anvil-tokens:jvmTest
./gradlew :anvil-cmp:compileKotlinDesktop :catalog:compileKotlinDesktop
./gradlew :anvil-material3:assembleDebug :catalog-expressive:assembleDebug
./gradlew :catalog:compileDebugKotlinAndroid
```

Install/run catalogs:

```bash
./gradlew :catalog:installDebug                 # CMP catalog on device/emulator
./gradlew :catalog-expressive:installDebug      # expressive catalog
./gradlew :catalog:run                          # desktop (CMP 1.9.3: compose desktop run task)
```

## Version pins (gradle/libs.versions.toml)

Gradle 9.5.1 · AGP 8.13.2 · Kotlin 2.2.21 · CMP 1.9.3 · androidx material3
1.4.0 (library) / 1.5.0-alpha26 (expressive catalog only) · BOM 2025.12.01.
If you bump CMP/Kotlin, verify the CMP↔Kotlin compatibility matrix first.

The 1.5.0-alpha26 AARs require minAgp 9.x/compileSdk 37; `:catalog-expressive`
opts out **app-locally** (a `CheckAarMetadataTask` disable in that module only).
The accompanying `android.suppressUnsupportedCompileSdk=37.0` flag lives in the
**root** `gradle.properties` — AGP's check runs before subproject properties are
read, so a module-local flag is a no-op. Library modules stay on the stable
paths — do not move the AAR-metadata workaround into shared config.
