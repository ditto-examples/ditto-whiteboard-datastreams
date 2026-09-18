# Testing

Whiteboard keeps the existing lightweight manual dependency container and uses
fakes at its `WhiteboardTransport` seam. The test stack is JUnit 4,
coroutines-test, Robolectric, Compose UI testing, Compose Preview Screenshot
Testing.

## Host-side verification

Run the same gate as CI:

```bash
cd whiteboard
./gradlew \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:bundleRelease \
  :app:compileDebugAndroidTestKotlin \
  :app:validateDebugScreenshotTest
```

Local JVM tests cover reducer convergence, operation gaps, protocol identity and
limits, snapshot assembly, viewport math, preview behavior, diagnostics,
presence layout, and ten-peer convergence. Executed Robolectric Compose tests
cover onboarding state restoration, confirmation, adaptive control reachability,
and connected-people semantics. Screenshot declarations span 400, 610, and
900 dp widths with 400, 500, and 1000 dp heights, plus dark theme and 1.5× font
scale. They also cover profile editing, both permission states, connected people,
and Troubleshooting on representative phone, landscape, and tablet windows.

Screenshot references are committed and CI validates every render. When an
intentional UI change occurs, run `./gradlew :app:updateDebugScreenshotTest`,
inspect every changed image, and commit the approved references.

## Physical-device acceptance

Host tests cannot certify nearby radios or end-to-end latency. Before a public
demo, use at least two physical devices and verify:

- permission rationale, denial, retry, permanent-denial Settings recovery, and
  local-preview fallback;
- discovery over LAN plus either Wi-Fi Aware or Bluetooth LE;
- simultaneous drawing and deterministic convergence for every tool;
- clear, eraser, profile edits, and incompatible protocol handling;
- late join, dropped/reconnected state stream, negative snapshot acknowledgment,
  process death, and snapshot retry;
- Troubleshooting peer removal and bounded error reporting.

The final rehearsal should use five to ten devices. Record frame timing at the
512-visible-object safety limit, p95 preview latency, and 512-object hydration time
rather than treating unmeasured targets as passed.

## Adding tests

- Put pure domain/protocol/session tests in `app/src/test`.
- Use a fake `WhiteboardTransport`; do not require live Ditto credentials in
  host tests or CI.
- Put system permission/radio and true multi-device tests in `app/src/androidTest`.
- Prefer semantic matchers in Compose tests and include state restoration for
  user-entered or dialog state.
- Every protocol limit or recovery fix needs a regression test reproducing the
  rejected input or lost-message shape.
