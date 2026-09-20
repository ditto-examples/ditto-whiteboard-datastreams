# Agent Guide

## Repository layout

- `whiteboard/` — Android (Kotlin/Compose) Whiteboard demo for the Ditto Data
  Streams API (Ditto Kotlin SDK 5.1.0).
- `whiteboard-apple/` — SwiftUI Whiteboard demo for macOS, iOS, and iPadOS,
  built against a locally built Ditto Swift SDK from the `main` branch of the
  ditto monorepo (`~/Developer/ditto`), targeting the Data Streams API that
  ships in Ditto SDK 5.2.0.
- `DittoDataStreams.md` — Data Streams API reference.
- `SKILL.md` — Data Streams integration guide (ownership, cancellation,
  reconnect).
- `docs/` — architecture, getting started, testing, releasing docs.

## Xcode / SwiftUI skills (installed in this repo)

The directory `docs/xcode-skills/` contains authoritative Apple-platform
skills. Load the matching skill before doing related work:

| Skill | Path | Use for |
|---|---|---|
| swiftui-specialist | `docs/xcode-skills/swiftui-specialist` | Any SwiftUI code: view structure, `@Observable`, `@Environment`/`@Entry`, `ForEach`/`List` identity, modifiers, localization, animations, soft-deprecated APIs. Consult before writing or reviewing SwiftUI. |
| swiftui-whats-new-27 | `docs/xcode-skills/swiftui-whats-new-27` | 2027 SDK APIs/behaviors: `@State` macro breaks, `@ContentBuilder`, toolbar changes, item-bound alerts/dialogs, reorderable, AsyncImage caching. |
| modernize-tests | `docs/xcode-skills/modernize-tests` | Writing/modernizing tests with Swift Testing (`@Test`, `#expect`, `#require`, `confirmation`); XCTest interop rules (UI tests stay XCTest). |
| device-interaction | `docs/xcode-skills/device-interaction` | Verifying UI on device/simulator via DeviceInteraction/Xcode MCP tools (install/run, hierarchy, screenshots, touch synthesis). |
| building-document-based-swiftui-applications | `docs/xcode-skills/building-document-based-swiftui-applications` | Document-based apps (new `Document` protocol, `DocumentGroup`, undo/autosave). |
| audit-xcode-security-settings | `docs/xcode-skills/audit-xcode-security-settings` | Auditing/hardening Xcode build settings (analyzer, pointer auth, Enhanced Security). |
| app-intents-specialist | `docs/xcode-skills/app-intents-specialist` | App Intents / Shortcuts integration correctness. |
| app-intents-whats-new-27 | `docs/xcode-skills/app-intents-whats-new-27` | App Intents APIs from iOS/macOS 26–27, incl. `AppIntentsTesting`. |
| adopt-c-bounds-safety | `docs/xcode-skills/adopt-c-bounds-safety` | Clang `-fbounds-safety` adoption in C code. |
| uikit-app-modernization | `docs/xcode-skills/uikit-app-modernization` | UIKit modernization (scene lifecycle etc.). |

## Building the custom Ditto Swift SDK

The SwiftUI app uses a locally built `DittoSwift.xcframework` (version
`5.2.0-dev`) from the ditto monorepo:

```bash
cd ~/Developer/ditto          # must be on main
make build-ios build-mac-all  # Rust FFI cores (arm64: iOS, iOS sim, macOS)
# then assemble the 3-slice xcframework (see whiteboard-apple/scripts/)
```

Data Streams on Swift is SPI-gated: use `@_spi(PreviewDataStreams) import
DittoSwift`, enable NGN via system parameters (`network_enable_ngn`,
`replication_over_ngn`), use `.smallPeersOnly` identity plus
`setOfflineOnlyLicenseToken(_:)`, and call `try ditto.sync.start()` before
using `ditto.dataStreams`.
