# 16 KB-aligned C++ runtime override

Ditto `5.1.0-preview.10` ships 16 KB-aligned Ditto native code but bundles a
4 KB-aligned `libc++_shared.so`. Android Gradle Plugin prioritizes this
app-owned copy from Android NDK `27.1.12297006`, whose load segments are aligned
to 16 KB for the `arm64-v8a` and `x86_64` ABIs used by Android's 16 KB
page-size devices. The 32-bit copies preserve support for legacy devices and
retain their platform-appropriate 4 KB alignment. The pinned Ditto API and
binaries remain unchanged.

Source paths:

- `aarch64-linux-android/libc++_shared.so` → `arm64-v8a`
- `arm-linux-androideabi/libc++_shared.so` → `armeabi-v7a`
- `i686-linux-android/libc++_shared.so` → `x86`
- `x86_64-linux-android/libc++_shared.so` → `x86_64`

All source files come from
`toolchains/llvm/prebuilt/<host>/sysroot/usr/lib/` in that NDK release. LLVM
libc++ is distributed under the Apache License 2.0 with LLVM Exceptions; see
the exact NDK-derived license and notice at
`../assets/third_party_licenses/LLVM-LICENSE.txt`. That file is packaged in the
app's assets and remains available with release artifacts.

SHA-256:

```text
e69496a4aeb51ebe61d0775ae9fefc76eee640f12317aa5174da67e5d4ec8fd6  arm64-v8a/libc++_shared.so
0a44a15c4136176405138ee034ed1be91802a03eaa8c48aea056955c928716f9  armeabi-v7a/libc++_shared.so
38c3601df215d76cae2cc89840789b6a968651abbdfae84d59cc59404de61b57  x86/libc++_shared.so
220f048039aab19e58132614392ff8e607ea2c3c2ddca26fa021455adbac4383  x86_64/libc++_shared.so
```
