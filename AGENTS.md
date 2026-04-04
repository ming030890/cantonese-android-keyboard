# AGENTS.md

## Repo rules

- Do not vendor or edit `qwen-asr` source directly in this repo.
- Keep `app/src/main/cpp/third_party/qwen-asr` as a Git submodule pointing at `https://github.com/ming030890/qwen-asr`.
- Do not commit `model.safetensors` in either model directory.
- Generated build output and local SDK/config files stay ignored.

## Setup workflow

1. Initialize the submodule:
   `git submodule update --init --recursive`
2. Put the upstream BF16 checkpoint in:
   `models/qwen3-asr-0.6b-bf16/`
3. Generate the local Q8 model file:
   `./scripts/generate_q8_model.sh`

The generation script:

- uses the submodule's `quantize_checkpoint.py`
- writes a local Q8 `model.safetensors`
- copies that generated file into:
  - `models/qwen3-asr-0.6b/model.safetensors`
  - `app/src/main/assets/models/qwen3-asr-0.6b/model.safetensors`
- refreshes the tokenizer/config sidecar files from the BF16 source dir

## Build and test workflow

- Native regression:
  `./tests/e2e_native_inference.sh`
- Android debug build:
  `./gradlew :app:assembleDebug`
- Install to device:
  `adb install -r app/build/outputs/apk/debug/app-debug.apk`

## Notes

- Streaming mode is used by the app when transcribing captured audio.
- The expected regression text is:
  `Hello，你好吗？已经坐低咗啦。`
