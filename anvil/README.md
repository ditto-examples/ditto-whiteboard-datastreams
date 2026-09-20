# Vendored Anvil

This is the source snapshot of the Anvil design system used by the Whiteboard
demo. It contains the SwiftUI `Anvil` package and the Android
`anvil-tokens` / `anvil-material3` modules, together with their token sources,
generators, tests, guidance, and license.

The Android modules are included directly by `whiteboard/settings.gradle.kts`
so they use the demo's AGP 9.2 / Kotlin 2.3 toolchain. The Swift package is a
local package dependency declared in `whiteboard-apple/project.yml`.

See [LICENSE.md](LICENSE.md) for the copied framework license.
