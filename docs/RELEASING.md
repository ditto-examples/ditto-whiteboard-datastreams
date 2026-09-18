# Release builds

## Build the distributable

```bash
cd whiteboard
./gradlew :app:bundleRelease
```

The unsigned bundle is written under `app/build/outputs/bundle/release/`. Use an
Android App Bundle for distribution so the store delivers only the device's ABI
and resources. Configure release signing through your organization’s secret
management; do not commit keystores or passwords.

## Optimization

Release builds enable R8 code optimization, obfuscation, and resource shrinking
with `proguard-android-optimize.txt`. `app/proguard-rules.pro` contains only
surgical rules for this app's generated protobuf messages and missing optional
annotations. AndroidX, Kotlin, coroutines, serialization, and Ditto supply their
own consumer rules.

Always run an installed, optimized release candidate through the physical-device
matrix in [Testing](TESTING.md). A successful R8 compile proves reachability
analysis, not runtime reflection/JNI behavior.

## Native 16 KB page compatibility

Ditto `5.1.0-preview.10` supplies a 16 KB-aligned `libdittoffi.so` but bundles a
4 KB-aligned C++ shared runtime. The app deliberately overrides only
`libc++_shared.so` with the redistributable Android NDK `27.1.12297006` build for
all four ABIs. Provenance and checksums live in
`app/src/main/jniLibs/README.md`.

After dependency or NDK changes, inspect every packaged native library for the
64-bit `arm64-v8a` and `x86_64` ABIs used by Android's 16 KB page-size devices,
rather than relying only on ZIP alignment:

```bash
python3 ../tools/verify_elf_alignment.py \
  app/build/outputs/bundle/release/app-release.aab
```

CI runs the same packaged-ELF gate and requires every `LOAD` segment in those
64-bit libraries to report alignment `0x4000` or greater. The script deliberately
does not impose a 16 KB requirement on the legacy 32-bit ABIs.

## Credentials and signing

Gradle embeds `DITTO_DATABASE_ID` and the offline license into `BuildConfig`.
Create builds with credentials dedicated to the intended environment and
audience. Keep `.env`, signing files, and signing passwords out of version
control and CI logs.
