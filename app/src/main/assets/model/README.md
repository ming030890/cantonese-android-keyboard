# Qwen3-ASR 0.6B local model package (Android JNI)

This project now uses native `qwen-asr` C inference via JNI.

## Model directory on device

Place model files under:

`/data/data/<your.package.name>/files/models/qwen3-asr-0.6b/`

Required model files:
- `model.safetensors`
- `config.json`
- `vocab.json`
- `merges.txt`

Recommended additional files:
- `generation_config.json`

## Native source status

The project is wired for native `qwen-asr` sources under:

`app/src/main/cpp/third_party/qwen-asr/`

If files are missing or incomplete, JNI builds in stub mode and model initialization fails with a clear error.

## Notes

- This app runs fully on-device.
- No backend server.
- No Python runtime in the Android app.
- `model.safetensors` may be the original BF16 checkpoint or the upstream Q8/int8 quantized variant.
- JNI wrapper supports both full decode (`qwen_transcribe_audio`) and streaming decode (`qwen_transcribe_stream`).
- Long audio is supported via segmentation controls (`segment_sec`, `search_sec`) and past-text conditioning.
