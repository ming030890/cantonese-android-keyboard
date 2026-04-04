#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SUBMODULE_DIR="$ROOT_DIR/app/src/main/cpp/third_party/qwen-asr"
SRC_DIR="${1:-$ROOT_DIR/models/qwen3-asr-0.6b-bf16}"
TMP_DIR="${TMPDIR:-/tmp}/qwen3-asr-0.6b-q8"
LOCAL_MODEL_DIR="$ROOT_DIR/models/qwen3-asr-0.6b"
ASSET_MODEL_DIR="$ROOT_DIR/app/src/main/assets/models/qwen3-asr-0.6b"

require_file() {
  local path="$1"
  if [[ ! -f "$path" ]]; then
    echo "missing required file: $path" >&2
    exit 1
  fi
}

if [[ ! -d "$SUBMODULE_DIR/.git" && ! -f "$SUBMODULE_DIR/.git" ]]; then
  echo "qwen-asr submodule is not initialized" >&2
  echo "run: git submodule update --init --recursive" >&2
  exit 1
fi

if [[ ! -d "$SRC_DIR" ]]; then
  echo "missing BF16 source model dir: $SRC_DIR" >&2
  exit 1
fi

require_file "$SRC_DIR/model.safetensors"
require_file "$SRC_DIR/config.json"
require_file "$SRC_DIR/vocab.json"
require_file "$SRC_DIR/merges.txt"

rm -rf "$TMP_DIR"
mkdir -p "$TMP_DIR" "$LOCAL_MODEL_DIR" "$ASSET_MODEL_DIR"

python3 "$SUBMODULE_DIR/quantize_checkpoint.py" \
  --src "$SRC_DIR" \
  --dst "$TMP_DIR"

cp -f "$TMP_DIR/model.safetensors" "$LOCAL_MODEL_DIR/model.safetensors"
cp -f "$TMP_DIR/model.safetensors" "$ASSET_MODEL_DIR/model.safetensors"

for name in config.json generation_config.json vocab.json merges.txt; do
  if [[ -f "$SRC_DIR/$name" ]]; then
    cp -f "$SRC_DIR/$name" "$LOCAL_MODEL_DIR/$name"
    cp -f "$SRC_DIR/$name" "$ASSET_MODEL_DIR/$name"
  fi
done

du -sh "$LOCAL_MODEL_DIR/model.safetensors" "$ASSET_MODEL_DIR/model.safetensors"
echo "Q8 model generated and copied into local + asset model directories."
