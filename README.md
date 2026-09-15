# Ditto Data Streams Whiteboard

An ephemeral, nearby-first collaborative whiteboard for Android. Up to ten peers share live drawing previews over an the Ditto Data Stream  API.

The Android project lives in `whiteboard/` and uses Jetpack Compose, Material 3, Navigation 3, adaptive supporting panes, protobuf-lite, DataStore, and Ditto `5.1.0-preview.10`.

## Documentation

- [Architecture & Data Streams walkthrough](docs/ARCHITECTURE.md) — layered architecture, the two-topic stream topology, and the late-join snapshot sequence, with diagrams.
- [Data Streams API reference](DittoDataStreams.md) and the [usage guide](SKILL.md).

## Configure

Copy `.env.sample` to `.env` in the repository root and fill it with dedicated Whiteboard SmallPeersOnly credentials:

```dotenv
DITTO_DATABASE_ID=
DITTO_LICENSE=
```

Gradle reads those values into the app's generated `BuildConfig`. The root `.env` file is ignored by git; `whiteboard/local.properties` remains an untracked legacy fallback. Do not reuse credentials from another demo. Without credentials, the app intentionally runs as a local drawing preview and explains the degraded state in its banner and troubleshooting screen.

The app follows the official v5 install guidance and depends on the Kotlin Multiplatform root module `com.ditto:ditto-kotlin`; Gradle resolves the platform-specific Android variant from its module metadata. The version catalog keeps the SDK in the 5.x lane (`require = "[5.0.1,6.0.0)"`, preferring 5.1.0) so minor and patch updates land without an unprompted major upgrade.

## Build and test

```bash
cd whiteboard
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:compileDebugAndroidTestKotlin :app:compileDebugScreenshotTestKotlin
```

Screenshot test declarations cover 400, 610, and 900 dp widths crossed with 400, 500, and 1000 dp heights, plus dark theme and 1.5× font scale. Reference images are deliberately not generated automatically; review the previews in Android Studio before accepting baselines.

## Runtime model

- `wb_live` is unreliable, drop-oldest, delta encoded, and capped at 30 updates per second.
- `wb_state` is reliable, ordered per peer, compression-requested, size checked, and chunked when needed.
- Reliable operations are immutable, idempotent, and replayed in total Lamport order.
- Completed freehand paths are simplified. Erasing deterministically splits freehand strokes and removes intersected shapes or text in full.
- Clear establishes a watermark that suppresses older drawing operations.
- Snapshots are merge-only transfers with SHA-256 validation, buffering during hydration, application acknowledgments, and retry after timeout.
- Nothing is written to Ditto Store. The board disappears after every participating process exits.

The app starts Ditto only after a profile exists, obtains runtime permissions from `DittoSyncPermissions`, publishes protocol metadata, retains both stream acceptors, and then starts sync. The lower peer key initiates both bidirectional streams to prevent duplicate connections.

## Device acceptance

Use at least two physical Android devices with the same credentials. Verify discovery, simultaneous drawing, every tool and color, deterministic erasure, clear, late-join snapshots, reconnect, process termination, troubleshooting diagnostics, LAN, and either Wi-Fi Aware or BLE. The final public-demo rehearsal should use five to ten devices when that hardware is available.
