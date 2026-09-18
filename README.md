# Ditto Data Streams Whiteboard

An ephemeral, nearby-first Android whiteboard that teaches Ditto Data Streams
through a working multi-device app. Up to ten peers exchange low-latency drawing
previews and reliable board operations without writing anything to Ditto Store.

New to Streams or Ditto? Start with the
[step-by-step getting-started guide](docs/GETTING_STARTED.md). It defines every
term, runs locally without credentials, and then walks through a two-device
session.

## Five-minute local preview

Prerequisites: Android Studio with JDK 17, Android SDK 37, and an Android 7.0
(API 24) or newer emulator/device.

```bash
cd whiteboard
./gradlew :app:installDebug
```

Launch **Whiteboard** and create a profile. With no credentials, local-preview
mode starts automatically and does not request nearby permissions. Drawing works
immediately; a banner clearly indicates that nearby collaboration is off.

## Connect nearby devices

1. Copy `.env.sample` to `.env`.
2. Add a dedicated Whiteboard `DITTO_DATABASE_ID` and `DITTO_LICENSE` supplied
   by your Ditto Portal project or project administrator.
3. Install the same build on two physical Android devices.
4. Grant the explained nearby Wi-Fi/Bluetooth permissions on both devices.
5. Use the same Whiteboard credentials on both devices, then draw.

`.env` and `whiteboard/local.properties` are ignored by Git. Do not reuse
credentials from another demo. The app embeds the offline license in its build,
so distribute builds only to their intended audience.

## What the sample demonstrates

- `wb_live`: unreliable, drop-oldest, delta-encoded preview segments capped near
  30 updates per second.
- `wb_state`: reliable, per-peer ordered delivery for immutable/idempotent board
  operations and profiles.
- Digest reconciliation: peers hash their canonical operation sets; any unequal
  digest triggers a merge-only snapshot exchange, including gaps below an
  otherwise identical latest operation.
- Merge-only, SHA-256-verified snapshots for late join and repair, with strict
  byte/chunk/operation limits, retry, and bounded buffers.
- Presence-driven connection ownership: the lower peer key initiates each pair
  of bidirectional streams, preventing duplicate connections.
- Explicit candidate, stream, acceptor, coroutine, and Ditto lifecycle ownership.
- Foreground-only nearby sync: backgrounding the activity stops Ditto sync and
  closes streams; returning reconnects and repairs from digests.

The board is process-lifetime state. When every participating process exits, the
board disappears.

## Documentation map

- [Getting started](docs/GETTING_STARTED.md) — prerequisites, glossary,
  credential setup, first two-device stream, and troubleshooting.
- [Architecture and Data Streams walkthrough](docs/ARCHITECTURE.md) — layers,
  two-topic topology, reconciliation, and late-join sequence.
- [Testing](docs/TESTING.md) — test layers, commands, CI, and physical-device
  acceptance.
- [Release builds](docs/RELEASING.md) — R8, signing, AAB output, native
  alignment, and verification.
- [Data Streams API reference](DittoDataStreams.md) — exact advanced SDK
  declarations.
- [Data Streams integration guide](SKILL.md) — advanced ownership,
  cancellation, and reconnect details.

## Build and verify

```bash
cd whiteboard
./gradlew \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:bundleRelease \
  :app:compileDebugAndroidTestKotlin \
  :app:validateDebugScreenshotTest
```

The release build enables R8 code/resource optimization. CI runs this same
host-side gate. Multi-peer discovery, convergence, reconnect, and latency still
require physical devices; follow the matrix in [Testing](docs/TESTING.md).
