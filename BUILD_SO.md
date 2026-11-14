# Build the Android JNI libraries

From this repository, run:

```sh
./deploy.sh --release --native-only
```

This builds both `libqwen_asr.so` and `libcantonese_engine.so` from the current
sibling `QwenASR` checkout, stages them under `app/src/main/jniLibs/arm64-v8a`,
and verifies the selected model assets. It does not install an app or upload to
Play. Use `./deploy.sh --release` for the complete checked App Bundle build.

## Pinned math dependencies

`config/android_native_dependencies.json` pins the accepted upstream revisions:

- OpenBLAS 0.3.34: `e0166008be8e466242aa76b2ff75ce3f0fbf574a`.
- KleidiAI: `6787251d9cc2f38a3a6024b11fd7ace10cde4cd9`.
- NDK 30.0.14904198, Android API 32, ARM64.

`scripts/build_android_native_dependencies.py` fetches missing source revisions,
builds static PIC archives, verifies AArch64 objects and the required symbols,
and records their checksums. OpenBLAS retains the accepted ARMV8, CBLAS-only,
single-thread configuration. The default persistent cache is
`~/Library/Caches/cantonese-voice-keyboard/native`, outside Documents/iCloud.
No `/private/tmp/OpenBLAS` or temporary KleidiAI checkout is required.

```sh
python3 scripts/build_android_native_dependencies.py --jobs 4
python3 scripts/build_android_native_dependencies.py --check
```

Set `NATIVE_DEPS_DIR` to choose another cache, `ANDROID_SDK_ROOT` to choose another
SDK, or `ANDROID_NDK_HOME` to select an explicit installed NDK. The cache identity
includes the pinned configuration, NDK metadata and compiler version; cached
archives must match recorded checksums. `--check` never downloads or builds.

The Rust link uses the NDK's API-32 ARM64 compiler, dot-product support and 16 KB
ELF segment alignment. The math archives are linked statically; no additional
OpenBLAS or KleidiAI shared library is needed on the device. The packed ASR model's
existing CPU-feature requirements remain unchanged.

`build/native-build-provenance.json` records the actual source revision, source
diff hash, dependency-manifest hash, link flags and staged library hashes. Build
outputs and dependency payloads are generated artifacts and must not be committed.

## R8 release regression

Build a minified release APK and identify a connected emulator, then run:

```sh
bash scripts/run_minified_native_smoke.sh emulator-5554 /path/to/app-release.apk
```

The emulator-only fixture loads the installed APK's own class loader and exercises
its preserved JNI class/method names for Quick, English, handwriting and ASR
configuration. It also checks WorkManager's generated database class, launches
the regular app and verifies the process stays alive. The Java fixture is outside
the application source sets and adds no classes or test libraries to the product.

This installs the minified base APK for code/ABI/startup checks. It does not validate
speech-model delivery or full speech inference. Use the complete local-testing
asset-pack installation from `deploy.sh` for a full functional app deployment.
