#!/usr/bin/env bash
# Builds a 3-slice (iphoneos, iphonesimulator, macosx) DittoSwift.xcframework
# from the ditto monorepo checkout at ~/Developer/ditto (main branch).
#
# Prereqs: the Rust FFI cores must already be built and staged:
#   cd ~/Developer/ditto
#   export PATH="$HOME/.rustup/toolchains/1.91.0-aarch64-apple-darwin/bin:$PATH"
#   make build-ios build-mac-all
#
# (If build-mac-all fails with "can't find crate for <proc-macro>" /
# mis-aligned LINKEDIT errors — a known issue when cargo is invoked with an
# explicit --target equal to the host triple — build the macOS core with:
#   cargo build --profile release-nounwind --lib --no-default-features \
#     --features fs-storage,encryption -p dittoffi
# then copy target/release-nounwind/libdittoffi.a to
# target/aarch64-apple-darwin/release-nounwind/ and re-run make with
#   -o ../../target/aarch64-apple-darwin/release-nounwind/libdittoffi.a)
#
# Output: whiteboard-apple/Frameworks/DittoSwift.xcframework

set -euo pipefail

DITTO_REPO="${DITTO_REPO:-$HOME/Developer/ditto}"
SWIFT_SDK_DIR="${DITTO_REPO}/sdks/swift"
WORKSPACE="${SWIFT_SDK_DIR}/Ditto.xcworkspace"
SCHEME="DittoSwift"
CONFIGURATION="Release"
BUILD="$(mktemp -d)/ditto-xcframework-build"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)/Frameworks"

build_slice() {
    local sdk="$1"
    echo "==> Building ${SCHEME} for ${sdk}"
    # Deployment-target overrides: the vendored ObjCCBOR subproject pins
    # IPHONEOS_DEPLOYMENT_TARGET=12.0 / MACOSX_DEPLOYMENT_TARGET=11.0, below
    # what the installed Xcode supports; the DittoSwift target itself already
    # uses these same (or higher) values, so a global override is safe.
    local target_override="IPHONEOS_DEPLOYMENT_TARGET=15.0"
    if [[ "${sdk}" == "macosx" ]]; then
        target_override="MACOSX_DEPLOYMENT_TARGET=12.0"
    fi
    xcrun xcodebuild \
        -workspace "${WORKSPACE}" \
        -scheme "${SCHEME}" \
        -sdk "${sdk}" \
        -configuration "${CONFIGURATION}" \
        -derivedDataPath "${BUILD}/deriveddata-${sdk}" \
        build \
        BUILD_LIBRARY_FOR_DISTRIBUTION=YES \
        OBJC_CBOR_MANGLING_PREFIX=Ditto \
        ENABLE_BITCODE=NO \
        CONFIG_BUILD_DIR="${BUILD}/${CONFIGURATION}-${sdk}" \
        CONFIGURATION_BUILD_DIR="${BUILD}/${CONFIGURATION}-${sdk}" \
        CODE_SIGN_STYLE="Manual" \
        DEVELOPMENT_TEAM="" \
        "${target_override}"
}

build_slice iphoneos
build_slice iphonesimulator
build_slice macosx

for sdk in iphoneos iphonesimulator macosx; do
    codesign --remove-signature "${BUILD}/${CONFIGURATION}-${sdk}/${SCHEME}.framework" 2>/dev/null || true
done

mkdir -p "${OUTPUT_DIR}"
rm -rf "${OUTPUT_DIR}/DittoSwift.xcframework"

xcrun xcodebuild -create-xcframework \
    -output "${OUTPUT_DIR}/DittoSwift.xcframework" \
    -framework "${BUILD}/${CONFIGURATION}-iphoneos/${SCHEME}.framework" \
    -framework "${BUILD}/${CONFIGURATION}-iphonesimulator/${SCHEME}.framework" \
    -framework "${BUILD}/${CONFIGURATION}-macosx/${SCHEME}.framework"

# Strip .swiftsourceinfo (not meant for distribution)
find "${OUTPUT_DIR}/DittoSwift.xcframework" -name "*.swiftsourceinfo" -delete 2>/dev/null || true

echo "==> Wrote ${OUTPUT_DIR}/DittoSwift.xcframework"
ls "${OUTPUT_DIR}/DittoSwift.xcframework"
