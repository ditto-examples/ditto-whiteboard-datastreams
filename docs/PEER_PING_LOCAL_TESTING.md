# Testing Peer Ping Locally

Quick guide for exercising the peer ping troubleshooting tool between a local
Mac and an Android device. For protocol details see [PEER_PING.md](PEER_PING.md);
for the full acceptance matrix see [TESTING.md](TESTING.md#peer-ping-checks).

## Prerequisites

- Both builds must come from the same branch and use credentials for the **same
  Ditto database** (the repo-root `.env` feeds both the Gradle build and the
  Xcode "Generate Ditto Credentials" script phase). Ping does not work in
  local-preview (no-credentials) mode.
- Both devices on the same LAN (mDNS), or within Bluetooth range.
- Android: grant **Nearby devices** (and Location, if prompted) when the app
  asks. macOS: allow **Local Network** and **Bluetooth** when prompted.
- Keep both apps in the foreground; backgrounding an app pauses its nearby
  networking and closes its ping streams.

## Run the apps

Android (device or emulator attached):

```bash
cd whiteboard
./gradlew :app:assembleDebug
adb -s <device-serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <device-serial> shell monkey -p com.ditto.whiteboard -c android.intent.category.LAUNCHER 1
```

macOS:

```bash
cd whiteboard-apple
xcodegen generate   # only needed if files were added/removed from the project
xcodebuild -project Whiteboard.xcodeproj -scheme Whiteboard \
  -destination 'platform=macOS' -configuration Debug build
open ~/Library/Developer/Xcode/DerivedData/Whiteboard-*/Build/Products/Debug/Whiteboard.app
```

## Exercise the ping tool

1. Wait until each app shows the other under **People on this board**
   (discovery usually takes a few seconds on LAN).
2. Open the board sidebar:
   - **Android:** sidebar → **Troubleshooting** → **Peer ping** card.
   - **Apple (macOS/iOS):** sidebar → **Transport** → **Peer ping** section.
3. Select the other device in the peer picker and choose **Run ping**.
4. Expected good result: five `Echo N: x.xx ms` lines, then
   "5 of 5 echoes verified" and "Ping completed." RTT includes app and network
   overhead; LAN values are typically single-digit to low-tens of ms.
5. Repeat in the opposite direction (initiate from the other device). Both
   peers bind a responder, so either side can initiate.

## Things worth trying (and what to expect)

| Scenario | Expected result |
| --- | --- |
| Draw on the canvas while a ping runs | Strokes keep syncing on both boards; ping completes. Ping never touches board state. |
| **Cancel ping** mid-run | "Ping cancelled."; previously verified echoes stay on screen; a fresh run can start immediately. |
| Background one app (home button / Cmd-H), then ping it | The ping reports the stream closed or no echo within 5 seconds — not a hang. Foregrounding the app restores pingability. |
| Kill one app mid-run (swipe away / Cmd-Q) | The running ping ends with "The ping stream closed before the echo arrived." or the 5 s timeout. |
| Ping a peer running an older build (pre-ping) | "Could not connect…" after the 5 s handshake limit. |
| Second run while one is in flight | The Run control is disabled while a run is active; the service also rejects concurrent runs with "A ping is already running." |
| Airplane mode on one device | Discovery collapses; the peer leaves the picker and any in-flight run fails with a closed stream or timeout. |

Note: the verified-echo count and RTT list are labelled with the peer key suffix
they belong to ("Results for peer …"), so stale results cannot be mistaken for
a newly selected peer's.

## Watching the logs

Android:

```bash
adb -s <device-serial> logcat -s DittoWhiteboard:D
```

(The transport logs a warning if the ping topic fails to bind — that failure is
non-fatal by design and must not affect board sync.)

macOS:

```bash
log stream --predicate 'process == "Whiteboard"' --level info
```

## Unit / integration tests

```bash
# Android: wire format, responder bounds, and Compose card tests
cd whiteboard
./gradlew :app:testDebugUnitTest --tests "com.ditto.whiteboard.transport.PeerPingTest" \
  --tests "com.ditto.whiteboard.ui.troubleshooting.PeerPingCardTest"

# Apple: pure protocol tests
cd whiteboard-apple/Packages/WhiteboardCore
swift test --filter PeerPingTests

# Apple: SDK-backed two-instance ping tests (signs the SDK framework first)
cd ../../  # whiteboard-apple/
bash scripts/test_whiteboard_kit.sh   # or: swift test --filter pingStopsWithNearbyLifecycleAndResumes
```
