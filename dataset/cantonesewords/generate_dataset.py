#!/usr/bin/env python3
"""Generate a words.hk Cantonese text/audio dataset with Edge TTS."""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import os
import shutil
import sys
import tempfile
import urllib.request
from pathlib import Path
from typing import Any


DEFAULT_SOURCE_URL = "https://words.hk/faiman/analysis/wordslist.json"
VOICE = "zh-HK-HiuMaanNeural"
MAX_TTS_ATTEMPTS = 3


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate a mono 16 kHz WAV dataset from words.hk keys."
    )
    parser.add_argument("--out", type=Path, default=Path("dataset"))
    parser.add_argument("--limit", type=int)
    parser.add_argument("--concurrency", type=int, default=3)
    parser.add_argument("--skip-existing", action="store_true")
    parser.add_argument("--force", action="store_true")
    parser.add_argument("--source-url", default=DEFAULT_SOURCE_URL)
    parser.add_argument("--voice", default=VOICE, choices=(VOICE,))
    args = parser.parse_args()

    if args.limit is not None and args.limit < 0:
        parser.error("--limit must be at least 0")
    if args.concurrency < 1:
        parser.error("--concurrency must be at least 1")
    if args.skip_existing and args.force:
        parser.error("--skip-existing and --force cannot be used together")
    return args


def clean_text(value: str) -> str:
    """Apply the exact source-key cleanup required by the dataset contract."""
    return value.replace("*", "").strip()


def cleaned_source_keys(payload: Any) -> list[str]:
    if not isinstance(payload, dict):
        raise ValueError("Source JSON must be an object; only object keys are supported")

    texts: list[str] = []
    seen: set[str] = set()
    for key in payload.keys():
        if not isinstance(key, str):
            continue
        text = clean_text(key)
        if text and text not in seen:
            seen.add(text)
            texts.append(text)
    return texts


def text_id(text: str) -> str:
    return hashlib.sha1(text.encode("utf-8")).hexdigest()


def download_json(url: str) -> Any:
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/json",
            "User-Agent": "cantonese-voice-keyboard-dataset/1.0",
        },
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


async def run_command(*command: str) -> tuple[int, str]:
    process = await asyncio.create_subprocess_exec(
        *command,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    stdout, stderr = await process.communicate()
    output = stderr if stderr else stdout
    return process.returncode, output.decode("utf-8", errors="replace").strip()


async def validate_wav(path: Path) -> tuple[bool, str]:
    if not path.is_file():
        return False, "WAV file does not exist"

    returncode, output = await run_command(
        "ffprobe",
        "-v",
        "error",
        "-select_streams",
        "a:0",
        "-show_entries",
        "stream=sample_rate,channels:format=duration",
        "-of",
        "json",
        str(path),
    )
    if returncode != 0:
        return False, f"ffprobe failed: {output or 'unknown error'}"

    try:
        probe = json.loads(output)
        streams = probe.get("streams", [])
        if len(streams) != 1:
            return False, f"expected one audio stream, found {len(streams)}"
        stream = streams[0]
        duration = float(probe["format"]["duration"])
        sample_rate = int(stream["sample_rate"])
        channels = int(stream["channels"])
    except (KeyError, TypeError, ValueError, json.JSONDecodeError) as exc:
        return False, f"invalid ffprobe output: {exc}"

    if duration <= 0.05:
        return False, f"duration {duration:.3f}s is not greater than 0.05s"
    if duration > 20.0:
        return False, f"duration {duration:.3f}s exceeds 20s"
    if sample_rate != 16_000:
        return False, f"sample rate is {sample_rate}, expected 16000"
    if channels != 1:
        return False, f"channel count is {channels}, expected 1"
    return True, ""


async def synthesize_mp3(text: str, voice: str, mp3_path: Path) -> None:
    import edge_tts

    last_error: Exception | None = None
    for attempt in range(MAX_TTS_ATTEMPTS):
        try:
            await edge_tts.Communicate(text=text, voice=voice).save(str(mp3_path))
            return
        except Exception as exc:  # edge-tts exposes several transport errors
            last_error = exc
            mp3_path.unlink(missing_ok=True)
            if attempt + 1 < MAX_TTS_ATTEMPTS:
                await asyncio.sleep(2**attempt)
    raise RuntimeError(f"edge-tts failed after {MAX_TTS_ATTEMPTS} attempts: {last_error}")


async def convert_to_wav(mp3_path: Path, wav_path: Path) -> None:
    returncode, output = await run_command(
        "ffmpeg",
        "-y",
        "-v",
        "error",
        "-i",
        str(mp3_path),
        "-ac",
        "1",
        "-ar",
        "16000",
        "-c:a",
        "pcm_s16le",
        str(wav_path),
    )
    if returncode != 0:
        raise RuntimeError(f"ffmpeg failed: {output or 'unknown error'}")


async def generate_item(
    text: str,
    audio_dir: Path,
    voice: str,
    semaphore: asyncio.Semaphore,
    skip_existing: bool,
    force: bool,
) -> tuple[dict[str, str] | None, dict[str, str] | None]:
    identifier = text_id(text)
    relative_path = f"audio/{identifier}.wav"
    wav_path = audio_dir / f"{identifier}.wav"

    async with semaphore:
        if wav_path.exists() and not force:
            valid, reason = await validate_wav(wav_path)
            if valid:
                return {"audio_filepath": relative_path, "text": text}, None
            if skip_existing:
                return None, {"text": text, "error": f"existing WAV is invalid: {reason}"}
            wav_path.unlink(missing_ok=True)

        mp3_path: Path | None = None
        temporary_wav: Path | None = None
        try:
            with tempfile.NamedTemporaryFile(
                prefix=f"{identifier}-", suffix=".mp3", dir=audio_dir, delete=False
            ) as handle:
                mp3_path = Path(handle.name)
            mp3_path.unlink()

            with tempfile.NamedTemporaryFile(
                prefix=f"{identifier}-", suffix=".wav", dir=audio_dir, delete=False
            ) as handle:
                temporary_wav = Path(handle.name)
            temporary_wav.unlink()

            await synthesize_mp3(text, voice, mp3_path)
            await convert_to_wav(mp3_path, temporary_wav)
            valid, reason = await validate_wav(temporary_wav)
            if not valid:
                raise RuntimeError(f"generated WAV is invalid: {reason}")
            os.replace(temporary_wav, wav_path)
            return {"audio_filepath": relative_path, "text": text}, None
        except Exception as exc:
            wav_path.unlink(missing_ok=True)
            return None, {"text": text, "error": str(exc)}
        finally:
            if mp3_path is not None:
                mp3_path.unlink(missing_ok=True)
            if temporary_wav is not None:
                temporary_wav.unlink(missing_ok=True)


def write_jsonl(path: Path, rows: list[dict[str, str]]) -> None:
    temporary_path = path.with_suffix(path.suffix + ".tmp")
    with temporary_path.open("w", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")
    os.replace(temporary_path, path)


def write_dataset_readme(out_dir: Path, source_url: str) -> None:
    content = f"""# Cantonese Words Audio Dataset

Generated from the JSON object keys at {source_url}. Each key has `*` removed
and surrounding whitespace stripped before deduplication. Audio uses only
`{VOICE}` and contains mono 16 kHz PCM WAV files.

- `audio/`: one deterministic SHA1-named WAV per successful text item
- `manifest.jsonl`: successful `audio_filepath` and `text` pairs
- `skipped.jsonl`: failed items and their errors
"""
    out_dir.joinpath("README.md").write_text(content, encoding="utf-8")


def require_system_tools() -> None:
    missing = [tool for tool in ("ffmpeg", "ffprobe") if shutil.which(tool) is None]
    if missing:
        raise RuntimeError(f"Missing required system tool(s): {', '.join(missing)}")


async def async_main(args: argparse.Namespace) -> int:
    require_system_tools()
    payload = await asyncio.to_thread(download_json, args.source_url)
    texts = cleaned_source_keys(payload)
    if args.limit is not None:
        texts = texts[: args.limit]

    out_dir = args.out.expanduser().resolve()
    audio_dir = out_dir / "audio"
    audio_dir.mkdir(parents=True, exist_ok=True)

    semaphore = asyncio.Semaphore(args.concurrency)
    tasks = [
        generate_item(
            text,
            audio_dir,
            args.voice,
            semaphore,
            args.skip_existing,
            args.force,
        )
        for text in texts
    ]
    results = await asyncio.gather(*tasks)
    manifest_rows = [row for row, _ in results if row is not None]
    skipped_rows = [skipped for _, skipped in results if skipped is not None]

    write_jsonl(out_dir / "manifest.jsonl", manifest_rows)
    write_jsonl(out_dir / "skipped.jsonl", skipped_rows)
    write_dataset_readme(out_dir, args.source_url)

    print(
        f"Processed {len(texts)} unique texts: "
        f"{len(manifest_rows)} succeeded, {len(skipped_rows)} skipped."
    )
    return 0


def main() -> int:
    args = parse_args()
    try:
        return asyncio.run(async_main(args))
    except (OSError, ValueError, RuntimeError, json.JSONDecodeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
