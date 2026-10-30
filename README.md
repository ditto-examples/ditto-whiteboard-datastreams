# Ditto Data Streams Whiteboard

An ephemeral, nearby-first Android and Apple whiteboard that teaches Ditto Data Streams
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

## Configure

Copy `.env.sample` to `.env` in the repository root and fill it with dedicated
Whiteboard SmallPeersOnly credentials:

```dotenv
DITTO_DATABASE_ID=
DITTO_LICENSE=
```

Gradle reads those values into the app's generated `BuildConfig`. The root
`.env` file is ignored by git; `whiteboard/local.properties` remains an
untracked legacy fallback. Do not reuse credentials from another demo. Without
credentials, the app intentionally runs as a local drawing preview and explains
the degraded state in its banner and troubleshooting screen.

The app follows the official v5 install guidance and depends on the Kotlin
Multiplatform root module `com.ditto:ditto-kotlin`; Gradle resolves the
platform-specific Android variant from its module metadata. The version catalog
pins the public SDK to `5.2.0`, matching the Apple app.

## Apple app (iOS, iPadOS, macOS)

The SwiftUI app uses the public `DittoSwiftPackage` at exactly `5.2.0`.
Swift Package Manager downloads the SDK; a local Ditto checkout or custom
xcframework is no longer required. Install Xcode and XcodeGen, then generate
and open the project:

```bash
cd whiteboard-apple
xcodegen generate
open Whiteboard.xcodeproj
```

Select the **Whiteboard** scheme and an iOS simulator, device, or **My Mac**.
The app reads the same repository-root `.env` during its build. Without
credentials it runs in local-preview mode. See [Testing](docs/TESTING.md) for
Apple package and app test commands.

## Connect nearby devices

1. Install the same build on two physical Android devices.
2. Grant the explained nearby Wi-Fi/Bluetooth permissions on both devices.
3. Use the same Whiteboard credentials on both devices, then draw.

The app embeds the offline license in its build, so distribute builds only to
their intended audience.

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

## Peer ping troubleshooting tool

Open the board sidebar and choose **Transport** on Apple or **Troubleshooting**
on Android. In **Peer ping**, select a discovered peer and press **Run ping**.
Both devices need this feature, matching database credentials, nearby access,
and an open, foreground Whiteboard app. Local-preview mode cannot ping.

The tool sends five 64-byte requests over a separate reliable Data Stream and
prints the round-trip time for each verified echo. A successful result confirms
that the remote app received and echoed those bytes. It does not certify board
convergence, identify which radio carried a request, or measure one-way latency.
You can cancel a run; backgrounding either app closes its ping streams.

See [Peer ping](docs/PEER_PING.md) for the protocol, failure messages, lifecycle,
and Swift/Android interoperability checks.

## Documentation map

- [Getting started](docs/GETTING_STARTED.md) — prerequisites, glossary,
  credential setup, first two-device stream, and troubleshooting.
- [Architecture and Data Streams walkthrough](docs/ARCHITECTURE.md) — layers,
  two-topic topology, reconciliation, and late-join sequence.
- [Peer ping](docs/PEER_PING.md) — the troubleshooting tool, wire format, and result interpretation.
  - [Testing peer ping locally](docs/PEER_PING_LOCAL_TESTING.md) — two-device quickstart, scenarios, and log capture.
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
