# AGENTS.md

## Repo rules

- Do not vendor or edit `qwen-asr` source directly in this repo.
- Keep `app/src/main/cpp/third_party/qwen-asr` as a Git submodule pointing at `https://github.com/ming030890/qwen-asr`.
- Do not commit `model.safetensors` in either model directory.
- Generated build output and local SDK/config files stay ignored.

## Setup workflow

Run a single setup step:
`./scripts/bootstrap_qwen_model_assets.sh`

Before running it, place the upstream BF16 checkpoint in:
`models/qwen3-asr-0.6b-bf16/`

The script is the local bootstrap for model setup. It:

- requires the `qwen-asr` submodule to be initialized
- uses the submodule's `quantize_checkpoint.py`
- generates the local Q8 `model.safetensors`
- copies that file into `models/qwen3-asr-0.6b/` and `app/src/main/assets/models/qwen3-asr-0.6b/`
- refreshes tokenizer/config sidecar files from the BF16 source dir

## Build and test workflow

- Native regression:
  `./tests/e2e_native_inference.sh`
- Android debug build:
  `./gradlew :app:assembleDebug`
- Install to device:
  `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- ASR performance benchmarking:
  use `skills/asr-benchmark/SKILL.md`

## Notes

- Streaming mode is used by the app when transcribing captured audio.
- The expected regression text is:
  `Hello，你好吗？已经坐低咗啦。`
- For on-device ASR slowness triage, prefer the benchmark workflow in `skills/asr-benchmark/SKILL.md` over ad hoc timing.
