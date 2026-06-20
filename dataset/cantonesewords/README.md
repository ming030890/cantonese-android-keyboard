# Cantonese Words Edge TTS Dataset Generator

This project builds a Cantonese text/audio pair dataset from the JSON object
keys in the [words.hk word list](https://words.hk/faiman/analysis/wordslist.json).
JSON values are ignored. Keys are cleaned by removing every `*`, stripping
surrounding whitespace, dropping empty results, and deduplicating the remaining
text. The exact cleaned text is sent to Edge TTS with the single supported voice,
`zh-HK-HiuMaanNeural`.

## Requirements

- Python 3.10 or newer
- `ffmpeg` and `ffprobe` on `PATH`
- Python dependencies from `requirements.txt`

Install the Python dependency:

```sh
python -m pip install -r requirements.txt
```

## Generate the dataset

Run from this directory:

```sh
python generate_dataset.py --limit 100 --out /Volumes/SSD/cantonesewords --skip-existing
```

This writes the generated dataset to the SSD while keeping the script in the
working repository. If `--out` is omitted, the default output directory is
`dataset`. The generator rebuilds both JSONL files from scratch on every run and
continues after individual synthesis, conversion, or validation failures.

```text
dataset/
  audio/
    <sha1>.wav
  manifest.jsonl
  skipped.jsonl
  README.md
```

Each manifest row has exactly these two fields:

```json
{"audio_filepath":"audio/<sha1>.wav","text":"優點"}
```

Useful options:

- `--out PATH`: output directory (default: `dataset`)
- `--limit N`: process only the first N cleaned, unique keys
- `--concurrency N`: maximum concurrent items (default: `3`)
- `--skip-existing`: reuse valid existing WAVs and log invalid ones as skipped
- `--force`: regenerate existing WAVs
- `--source-url URL`: override the source JSON URL
- `--voice zh-HK-HiuMaanNeural`: the only accepted Edge TTS voice

WAV validation requires a duration greater than 0.05 seconds and at most 20
seconds, a 16000 Hz sample rate, and one channel. Failed rows are written to
`skipped.jsonl` with their cleaned text and an error message.
