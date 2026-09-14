# Patch policy / M1 runtime experiment

SillyTavern patches: **none**. `upstream/SillyTavern` remains the official fixed commit.
Only the generated Node source under `build/node-source/` is patched. Node is not
forked into the shell repository. Changes are recorded here and pinned by SHA256
in `upstream.lock.json`; `git apply --check` failures stop the build.

## node-runtime/0001-separate-host-generated-files.patch

- Node version: 26.8.2, official source archive pinned in `upstream.lock.json`.
- Reproduction: Android arm64 cross-build with `--shared --ninja` on Linux x86_64
  fails before compilation: `multiple rules generate gen/inspector-generated-output-root/src/js_protocol.stamp`.
- Reason: host and target GYP toolsets both produce files under the same shared
  intermediate directory. This patch separates `obj.host/gen` and `obj/gen`.
- Source/credit: Termux's package patch at commit
  `299d2c6d225dd5f9da626eba34cedd996ed2404c`,
  `packages/nodejs/tools-gyp-pylib-gyp-generator-ninja.py.patch`:
  https://github.com/termux/termux-packages/blob/299d2c6d225dd5f9da626eba34cedd996ed2404c/packages/nodejs/tools-gyp-pylib-gyp-generator-ninja.py.patch
- License: Termux package patches follow the patched package's license; see
  `licenses/Termux-package-patches.txt`, Node's bundled license and gyp's license.
- Scope: build-time generated paths only; no JS/network/authentication changes.
- Validation: `python3 tools/build_node.py`; Ninja must generate and compile the
  graph without suppressing duplicate-output diagnostics. Android runtime tests
  remain required separately.
- Removal: when the pinned upstream Node/GYP version supports separate host/target
  outputs without this patch. No upstream PR has been submitted by this project.

## node-runtime/0002-android-cross-host-link.patch

- Reproduction: Linux `mksnapshot` link fails with missing
  `TryHandleSignal`, `RegisterDefaultTrapHandler`, `v8_internal_simulator_ProbeMemory`,
  and `__atomic_compare_exchange` in the Android cross-build.
- Fix: include the existing POSIX/simulator trap-handler implementations **only
  for the Linux x64 host toolset of the arm64 cross-build**. This does not enable
  trap handling on Android or remove V8's Android security check. Unlike the
  broader Termux trap-header patch, it leaves target runtime capabilities alone.
- Link atomic support when the GYP target OS is Android as well as Linux. This
  one-line condition follows Termux's `tools-v8_gypfiles-v8.gyp.patch` at the
  same reference commit as above. The host additionally needs its real GCC
  `libatomic.so`: the NDK's earlier-search-path compatibility archive does not
  define the generic compare-exchange symbol. `build_node.py` resolves the host
  library directory via `g++ -print-file-name=libatomic.so`; no host library is
  copied into the APK.
- Validation/removal: `python3 tools/build_node.py` must link and execute the
  host snapshot tool and link the target library; remove when upstream GYP
  handles the host/target distinction and atomics without this patch.
- No upstream PR has been submitted by this project. New changes in this patch
  target the BSD-licensed V8 GYP build description; its upstream notices remain
  in the pinned Node source/license bundle.

## node-runtime/0003-link-android-cpufeatures.patch

- Reproduction: the Android `openssl-cli` dependency fails to link:
  `undefined symbol: android_getCpuFeatures`, referenced by bundled zlib.
- Fix: compile the pinned NDK's `sources/android/cpufeatures/cpu-features.c`
  into the Android target zlib library. The existing zlib code already calls
  this API; GYP provided its header but omitted its implementation. GYP/Ninja
  rejects absolute source paths, so the outer build tool copies the unmodified
  `.c`, `.h` and NOTICE into the generated tree's `deps/zlib/android-cpufeatures/`.
  Each input SHA256 is pinned in the lock; neither the NDK nor ST is edited.
- Scope: target build linkage, not hardcoded CPU feature detection or disabled
  SIMD. The NDK implementation and license are included in the pinned SDK/NOTICE.
- Validation: `python3 tools/build_node.py` must link OpenSSL and libnode; runtime
  HTTP/WASM and later ST compression/image tests remain necessary.
- Removal: when upstream bundles/links a supported CPU feature detection
  implementation itself. No upstream PR has been submitted by this project.

## Explicit probe-build configuration (not a source patch)

The first M1 build uses `--v8-disable-temporal-support`. With the currently installed
cross-toolchain, Node's configure detects no Rust/Cargo and would disable Temporal
automatically. The explicit option makes this difference deterministic and visible.
**Full ICU, Unicode regex, WebAssembly/SIMD and worker_threads remain enabled.**
Temporal is not a required ST capability in the audited snapshot, but this probe
must not be advertised as supporting every optional stock Node 26 feature.
Revisit Temporal/Rust cross-compilation before finalizing production runtime policy.

`--openssl-no-asm` follows Node's own `android_configure.py` recipe. Without it,
this cross-build selected x86 assembly for the arm64 OpenSSL target and failed
with invalid `=a` / `+d` constraints. It preserves TLS/certificate validation,
but disables assembly optimizations; it does not disable OpenSSL. Benchmark this
choice before production rather than assuming desktop crypto performance.

Node's own configure also applies its bundled ICU floating patch; that upstream
patch is in the pinned source archive, not an unrecorded shell modification.

Do not copy all Termux patches or binaries into the APK. Only demonstrated build
failures justify additional minimal patches. Do not delete ST abort/CSRF logic to
make background tests pass.
