#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
QWEN_DIR="$ROOT_DIR/app/src/main/cpp/third_party/qwen-asr"
MODEL_DIR="$ROOT_DIR/models/qwen3-asr-0.6b"
OPUS_INPUT="$ROOT_DIR/PTT-20241208-WA0011.opus"
OUT_DIR="$ROOT_DIR/tests/out"
WAV_INPUT="$OUT_DIR/PTT-20241208-WA0011.wav"
BIN_PATH="$OUT_DIR/qwen_asr_e2e"
RAW_TXT_NORMAL="$OUT_DIR/transcription_raw_normal.txt"
RAW_TXT_STREAM="$OUT_DIR/transcription_raw_stream.txt"
TXT_OUT_NORMAL="$OUT_DIR/transcription_normal.txt"
TXT_OUT_STREAM="$OUT_DIR/transcription_stream.txt"
EXPECTED_TEXT="Hello，你好吗？已经坐低咗啦。"
BUILD_ONLY=0

if [[ $# -gt 0 ]]; then
  case "$1" in
    --build-only)
      BUILD_ONLY=1
      ;;
    *)
      echo "unknown arg: $1" >&2
      exit 2
      ;;
  esac
fi

mkdir -p "$OUT_DIR"

require_file() {
  local path="$1"
  if [[ ! -f "$path" ]]; then
    echo "missing required file: $path" >&2
    exit 1
  fi
}

require_file "$OPUS_INPUT"
require_file "$MODEL_DIR/model.safetensors"
require_file "$MODEL_DIR/config.json"
require_file "$MODEL_DIR/vocab.json"
require_file "$MODEL_DIR/merges.txt"
require_file "$QWEN_DIR/qwen_asr.c"
require_file "$QWEN_DIR/qwen_asr.h"

echo "[e2e] converting opus -> wav (16k mono pcm16)"
ffmpeg -y -i "$OPUS_INPUT" -ac 1 -ar 16000 -sample_fmt s16 "$WAV_INPUT" >/dev/null 2>&1

arch_src="$QWEN_DIR/qwen_asr_kernels_neon.c"
if [[ "$(uname -m)" == "x86_64" ]]; then
  arch_src="$QWEN_DIR/qwen_asr_kernels_avx.c"
fi
require_file "$arch_src"

echo "[e2e] compiling native e2e harness"
clang -O3 -ffast-math -std=c11 -Wall -Wextra \
  -I"$QWEN_DIR" \
  "$ROOT_DIR/tests/native_e2e_transcribe.c" \
  "$QWEN_DIR/qwen_asr.c" \
  "$QWEN_DIR/qwen_asr_encoder.c" \
  "$QWEN_DIR/qwen_asr_decoder.c" \
  "$QWEN_DIR/qwen_asr_tokenizer.c" \
  "$QWEN_DIR/qwen_asr_safetensors.c" \
  "$QWEN_DIR/qwen_asr_kernels.c" \
  "$QWEN_DIR/qwen_asr_kernels_generic.c" \
  "$arch_src" \
  "$QWEN_DIR/qwen_asr_audio.c" \
  -lm -lpthread \
  -o "$BIN_PATH"

if [[ "$BUILD_ONLY" -eq 1 ]]; then
  echo "[e2e] build-only done"
  exit 0
fi

run_mode() {
  local mode="$1"
  local raw_out="$2"
  local text_out="$3"

  echo "[e2e] running transcription (mode=$mode)"
  "$BIN_PATH" "$MODEL_DIR" "$WAV_INPUT" "Cantonese" "$mode" > "$raw_out"

  # Keep only final non-empty line; native library may print debug/info lines.
  grep -v '^$' "$raw_out" | tail -n 1 > "$text_out" || true
  if [[ ! -s "$text_out" ]]; then
    echo "[e2e] FAIL: transcription output is empty (mode=$mode)" >&2
    exit 1
  fi

  local char_count
  char_count=$(wc -m < "$text_out" | tr -d ' ')
  if [[ "$char_count" -lt 2 ]]; then
    echo "[e2e] FAIL: transcription too short ($char_count chars, mode=$mode)" >&2
    exit 1
  fi

  local actual_text
  actual_text="$(cat "$text_out")"
  if [[ "$actual_text" != "$EXPECTED_TEXT" ]]; then
    echo "[e2e] FAIL: transcription mismatch (mode=$mode)" >&2
    echo "[e2e] expected: $EXPECTED_TEXT" >&2
    echo "[e2e] actual:   $actual_text" >&2
    exit 1
  fi

  echo "[e2e] PASS (mode=$mode): produced $char_count chars"
  echo "[e2e] transcription preview ($mode):"
  sed -n '1p' "$text_out"
}

run_mode "normal" "$RAW_TXT_NORMAL" "$TXT_OUT_NORMAL"
run_mode "stream" "$RAW_TXT_STREAM" "$TXT_OUT_STREAM"
