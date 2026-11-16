#!/usr/bin/env python3
"""Check required distribution notices and privacy hygiene in source assets and the actual APK or app bundle."""
import argparse
from pathlib import Path
import zipfile

REQUIRED_NOTICES = (
    "licenses/Qwen3-ASR-Cantonese-HK.txt",
    "licenses/QwenASR.txt",
    "licenses/Silero-VAD.txt",
    "licenses/KleidiAI.txt",
    "licenses/OpenBLAS.txt",
    "licenses/SCOWL.txt",
    "licenses/english_dictionary/DERIVATION.txt",
    "licenses/english_dictionary/README_en_GB.txt",
    "licenses/english_dictionary/GPL-2.0.txt",
    "licenses/english_dictionary/LGPL-2.1.txt",
    "emoji/ANDROIDX-LICENSE.txt",
    "emoji/UNICODE-LICENSE.txt",
)

REQUIRED_ASR_TOKENS = (
    "Qwen3-ASR-1.7B-Cantonese-HK",
    "joe2643/Qwen3-ASR-1.7B-Cantonese-HK",
    "Qwen/Qwen3-ASR-1.7B",
)


def verify(project: Path, package: Path | None = None) -> None:
    assets = project / "app/src/main/assets"
    for notice in REQUIRED_NOTICES:
        source = assets / notice
        if not source.is_file() or not source.read_bytes().strip():
            raise ValueError(f"Missing distribution notice: {notice}")

    asr_notice = (assets / "licenses/Qwen3-ASR-Cantonese-HK.txt").read_text(encoding="utf-8")
    for token in REQUIRED_ASR_TOKENS:
        if token not in asr_notice:
            raise ValueError(f"ASR open-source notice is missing required model reference: {token}")

    if package is not None:
        if package.suffix not in {".apk", ".aab"}:
            raise ValueError("Expected APK or AAB")
        prefix = "base/" if package.suffix == ".aab" else ""
        with zipfile.ZipFile(package) as bundle:
            names = set(bundle.namelist())
            for notice in REQUIRED_NOTICES:
                name = f"{prefix}assets/{notice}"
                if name not in names:
                    raise ValueError(f"Distribution notice was excluded from the bundle: {notice}")
                content = bundle.read(name)
                if content != (assets / notice).read_bytes():
                    raise ValueError(f"Bundle contains an outdated distribution notice: {notice}")
            if "release" in package.parts or "release" in package.stem:
                if f"{prefix}res/xml/network_security_config.xml" in names:
                    raise ValueError("Release bundle must not include debug network_security_config.xml")
    print(f"Verified {len(REQUIRED_NOTICES)} distribution notices" + (" in bundle" if package else " in source"))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--package", type=Path)
    args = parser.parse_args()
    try:
        verify(args.project.resolve(), args.package)
    except (ValueError, OSError, zipfile.BadZipFile) as error:
        parser.exit(1, f"Release asset verification failed: {error}\n")
