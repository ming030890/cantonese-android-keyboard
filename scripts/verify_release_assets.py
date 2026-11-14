#!/usr/bin/env python3
"""Check required distribution notices in source assets and the actual APK or app bundle."""
import argparse
from pathlib import Path
import zipfile

REQUIRED_NOTICES = (
    "emoji/ANDROIDX-LICENSE.txt",
    "emoji/UNICODE-LICENSE.txt",
    "licenses/SCOWL.txt",
    "licenses/QwenASR.txt",
    "licenses/Qwen3-ASR-Cantonese-HK.txt",
    "licenses/OpenBLAS.txt",
    "licenses/KleidiAI.txt",
    "licenses/english_dictionary/DERIVATION.txt",
    "licenses/english_dictionary/README_en_GB.txt",
    "licenses/english_dictionary/GPL-2.0.txt",
    "licenses/english_dictionary/LGPL-2.1.txt",
)


def verify(project: Path, package: Path | None = None) -> None:
    assets = project / "app/src/main/assets"
    for notice in REQUIRED_NOTICES:
        source = assets / notice
        if not source.is_file() or not source.read_bytes().strip():
            raise ValueError(f"Missing distribution notice: {notice}")
    if package is not None:
        if package.suffix not in {".apk", ".aab"}:
            raise ValueError("Expected APK or AAB")
        prefix = "base/" if package.suffix == ".aab" else ""
        with zipfile.ZipFile(package) as bundle:
            for notice in REQUIRED_NOTICES:
                name = f"{prefix}assets/{notice}"
                try:
                    content = bundle.read(name)
                except KeyError as error:
                    raise ValueError(f"Distribution notice was excluded from the bundle: {notice}") from error
                if content != (assets / notice).read_bytes():
                    raise ValueError(f"Bundle contains an outdated distribution notice: {notice}")
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
