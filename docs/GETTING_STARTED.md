# Getting started with Ditto Data Streams Whiteboard

This guide assumes no prior Ditto or Data Streams experience. You will first run
the app as a local whiteboard, then connect two physical devices and trace one
drawing stroke through the code.

## 1. Prerequisites

- Android Studio with its bundled JDK 17, or another JDK 17 installation
- Android SDK 37
- An Android 7.0 (API 24) or newer emulator/device for local preview
- Two physical Android devices for nearby collaboration
- For collaboration, a dedicated Ditto database ID and offline license token
  from your Ditto Portal project or project administrator

The Gradle wrapper downloads the remaining build dependencies. Data Streams is a
preview API in the pinned Ditto SDK, so this sample deliberately keeps the exact
SDK coordinate in `whiteboard/gradle/libs.versions.toml`.

## 2. Learn the vocabulary

| Term | Meaning in this app |
|---|---|
| Peer | One running Whiteboard process with a Ditto public peer key. |
| Presence | Ditto's nearby topology view. The app uses it to discover peers and transports. |
| Topic | A short name that identifies a kind of stream. Whiteboard uses `wb_live` and `wb_state`. |
| Acceptor | The retained result of `bindTopic`; it keeps a topic available for inbound connections. |
| Candidate | A callback-scoped opportunity to open a stream. Call `take()` before handing it to another coroutine. |
| Stream | A bidirectional peer-to-peer byte channel opened for one topic and reliability mode. |
| Reliable | Ordered transport used for state that must converge. The app still adds retry, digest comparison, and snapshots because application queues and processes can fail. |
| Unreliable | Low-latency transport used for previews that may be dropped or reordered. |
| Backpressure | A bound on queued work. Whiteboard bounds queues/transfers and repairs saturation with a later snapshot. |
| Snapshot | A size-bounded, digest-verified operation set used to hydrate a late peer or repair a gap. |

Data Streams moves bytes; it does not define your application protocol. This
sample's protobuf envelopes, operation validation, reconciliation, and limits are
the application protocol layered on top.

## 3. Run without Ditto credentials

From the repository root:

```bash
cd whiteboard
./gradlew :app:installDebug
```

Open the app and create a profile. Because the build has no credentials,
local-preview mode starts automatically and Android does not show a nearby
permission prompt. Expected result:

- every drawing tool works;
- zoom and two-finger pan work;
- the people list contains only you;
- a banner says nearby collaboration is unavailable;
- Troubleshooting reports **Local preview** mode.

This fallback is intentional. It lets a new developer explore the UI and domain
model before configuring networking.

## 4. Add Whiteboard credentials

From the repository root:

```bash
cp .env.sample .env
```

Edit `.env`:

```dotenv
DITTO_DATABASE_ID=your-whiteboard-database-id
DITTO_LICENSE=your-whiteboard-offline-license-token
```

Both devices must use credentials for the same Ditto database. Keep `.env`
private; Git ignores it. Gradle writes these values into `BuildConfig`, which
means the resulting APK/AAB contains the offline token.

Rebuild:

```bash
cd whiteboard
./gradlew :app:installDebug
```

## 5. Connect the first two devices

1. Install the same build on both physical devices.
2. Open Whiteboard and create a distinct profile on each.
3. Read the nearby-access explanation and grant the requested Android
   permissions.
4. Keep Bluetooth and Wi-Fi enabled.
5. Open **Connected people**. Each device should list the other.
6. Draw on device A. Device B should show a translucent live preview followed by
   the committed object.
7. Force-stop and reopen device B while A stays open. B should receive a
   snapshot and converge.

The app works over whichever enabled nearby transport Ditto discovers: LAN,
Wi-Fi Aware, and/or Bluetooth LE. Troubleshooting shows the actual transport and
the connection state for both topics.

## 6. Trace one stroke through the code

1. `ui/board/BoardCanvas.kt` collects bounded pointer samples.
2. `data/BoardSession.kt` sends recent preview segments and creates one immutable
   commit operation at gesture end.
3. `protocol/WhiteboardProtocol.kt` validates and encodes application messages.
4. `transport/DittoWhiteboardTransport.kt` fans previews over `wb_live`, queues
   reliable operations per peer on `wb_state`, and owns reconnect/snapshot work.
5. Remote messages return through `BoardSession`, which de-duplicates operations
   and folds them through `domain/BoardReducer.kt`.
6. `WhiteboardViewModel` exposes immutable UI state collected with
   `collectAsStateWithLifecycle`.

Read [the architecture walkthrough](ARCHITECTURE.md) next for diagrams of these
paths.

## 7. Common problems

### The app stays in local preview

- Confirm both `.env` values are nonblank.
- Rebuild after editing `.env`; credentials are read at Gradle configuration.
- Confirm both devices use the same database ID.

### A peer does not appear

- Grant nearby permissions on both devices. If previously denied permanently,
  use the app's **Open Settings** action.
- Enable Wi-Fi and Bluetooth.
- Keep both apps in the foreground for the first discovery attempt.
- Check Troubleshooting for the transport and last privacy-safe error.

### A peer appears but the board differs

- Open Troubleshooting and inspect `wb_state`.
- Reconnect the affected peer. `Hello` compares canonical state digests; any
  difference triggers a merge-only snapshot.
- Record the exact app build, device models, Android versions, and transport when
  reporting a reproducible failure.

### Gradle cannot find the SDK

Create `whiteboard/local.properties` with your Android SDK location, or open the
project in Android Studio and let it configure the file. Start from
`whiteboard/local.properties.example`.

## 8. Trust and resource limits

This is a nearby collaboration demo for devices authorized with the same Ditto
credentials; it is not a hostile multi-tenant service. Direct operation
envelopes are bound to the connected peer and all frames are schema/size
validated. A snapshot sender can, however, relay schema-valid operations
attributed to another peer: the stream authenticates the relay, not every
historical author. Treat every device using the same database credentials as
fully trusted.

Display names and colors are stored on their originating device. Profile update
operations remain in the in-memory board history and may be re-shared to peers
that join later, until every participating process exits.

Snapshots are limited to 16 MiB, 4,096 chunks, 10,000 operations, 128 erase
operations, and 64 authors. At most 512 objects are visible at once; strokes
carry at most 128 points and erasers at most 32. Only one snapshot is hydrated
at a time; at most 64 new operations or 2 MiB are buffered during it. Partial
transfers and queues have bounded lifetimes/capacity. Every newly discovered,
admitted, protocol-compatible peer gates editing until its first reconciliation;
if it remains unreachable
for 30 seconds, editing unlocks in an explicitly degraded state rather than
freezing forever.

The 10,000-operation and 128-erase bounds are terminal for an ephemeral board and deliberately
prevent unbounded memory use. One device restarting does not reset it because a
surviving peer will rehydrate the same history. To start a new board after the
limit, close Whiteboard on every participating device, wait for peers to
disappear, and then reopen it.
