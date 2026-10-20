#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENGINE_DIR="$PROJECT_DIR/../QwenASR/crates/cantonese-ngram"
HOST_TARGET="$PROJECT_DIR/app/build/english-host"
case "$(uname -s)" in
  Darwin) LIBRARY="libcantonese_engine.dylib" ;;
  Linux) LIBRARY="libcantonese_engine.so" ;;
  *) echo "Host JNI autocorrect tests support macOS and Linux." >&2; exit 1 ;;
esac
CARGO_TARGET_DIR="$HOST_TARGET" cargo build --manifest-path "$ENGINE_DIR/Cargo.toml" --release --features android --lib
cd "$PROJECT_DIR"
./gradlew :app:testDebugUnitTest --tests '*SharedLmEnglishScenarioTest' \
  "-PenglishNativeLibrary=$HOST_TARGET/release/$LIBRARY" --console=plain
