#!/usr/bin/env bash
# SwiftPM does not sign the public SDK's macOS framework when building package
# tests. Ad-hoc sign only the build copy so the macOS test runner can load it.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/Packages/WhiteboardKit"

swift build --build-tests
BIN_DIR="$(swift build --show-bin-path)"
codesign --force --sign - "$BIN_DIR/DittoSwift.framework"
swift test --skip-build
