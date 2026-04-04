#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="$ROOT_DIR/tests/out"
BIN_PATH="$OUT_DIR/qwen_asr_e2e"
MODEL_DIR="$ROOT_DIR/models/qwen3-asr-0.6b"
WAV_INPUT="$OUT_DIR/PTT-20241208-WA0011.wav"
RAW_OUT="$OUT_DIR/bench_transcription_raw.txt"
LAT_FILE="$OUT_DIR/latency_runs_sec.txt"
REPORT="$OUT_DIR/latency_report.txt"
RUNS="${1:-3}"
MODE="${2:-normal}"

if ! [[ "$RUNS" =~ ^[0-9]+$ ]] || [[ "$RUNS" -lt 1 ]]; then
  echo "usage: $0 [runs>=1] [normal|stream]" >&2
  exit 2
fi
if [[ "$MODE" != "normal" && "$MODE" != "stream" ]]; then
  echo "usage: $0 [runs>=1] [normal|stream]" >&2
  exit 2
fi

mkdir -p "$OUT_DIR"

# Ensure binary and converted WAV exist.
"$ROOT_DIR/tests/e2e_native_inference.sh" --build-only >/dev/null

if [[ ! -x "$BIN_PATH" ]]; then
  echo "benchmark: missing binary $BIN_PATH" >&2
  exit 1
fi
if [[ ! -f "$WAV_INPUT" ]]; then
  echo "benchmark: missing wav input $WAV_INPUT" >&2
  exit 1
fi

if ! command -v ffprobe >/dev/null 2>&1; then
  echo "benchmark: ffprobe not found" >&2
  exit 1
fi

audio_sec="$(ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 "$WAV_INPUT")"
: > "$LAT_FILE"

echo "[bench] runs: $RUNS"
echo "[bench] mode: $MODE"
echo "[bench] audio seconds: $audio_sec"

for i in $(seq 1 "$RUNS"); do
  start="$(perl -MTime::HiRes=time -e 'printf "%.6f", time')"
  "$BIN_PATH" "$MODEL_DIR" "$WAV_INPUT" "Cantonese" "$MODE" > "$RAW_OUT"
  end="$(perl -MTime::HiRes=time -e 'printf "%.6f", time')"

  elapsed="$(awk -v s="$start" -v e="$end" 'BEGIN { printf "%.6f", (e - s) }')"
  echo "$elapsed" >> "$LAT_FILE"

  text_line="$(grep -v '^$' "$RAW_OUT" | tail -n 1 || true)"
  if [[ -z "$text_line" ]]; then
    echo "[bench] FAIL: empty transcription on run $i" >&2
    exit 1
  fi

  rtf="$(awk -v a="$audio_sec" -v t="$elapsed" 'BEGIN { if (t > 0) printf "%.3f", (a / t); else print "0.000" }')"
  echo "[bench] run $i: ${elapsed}s (${rtf}x realtime)"
done

avg="$(awk '{s+=$1} END { if (NR>0) printf "%.6f", s/NR; else print "0.000000" }' "$LAT_FILE")"
min="$(sort -n "$LAT_FILE" | head -n 1)"
max="$(sort -n "$LAT_FILE" | tail -n 1)"
count="$(wc -l < "$LAT_FILE" | tr -d ' ')"
mid=$(( (count + 1) / 2 ))
p50="$(sort -n "$LAT_FILE" | sed -n "${mid}p")"
avg_rtf="$(awk -v a="$audio_sec" -v t="$avg" 'BEGIN { if (t > 0) printf "%.3f", (a / t); else print "0.000" }')"

{
  echo "runs=$count"
  echo "mode=$MODE"
  echo "audio_sec=$audio_sec"
  echo "avg_sec=$avg"
  echo "p50_sec=$p50"
  echo "min_sec=$min"
  echo "max_sec=$max"
  echo "avg_realtime_x=$avg_rtf"
} | tee "$REPORT"
