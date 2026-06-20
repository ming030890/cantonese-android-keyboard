"""Keep generated dataset artifacts on the configured SSD."""

from __future__ import annotations

import os
from pathlib import Path


SSD_ROOT = Path(os.environ.get("HK_PROPER_NAMES_DATA_ROOT", "/Volumes/SSD/hk-proper-nouns"))


def data_path(path: str | Path) -> tuple[Path, Path | None]:
    requested = Path(path)
    if requested.is_absolute() or requested.is_symlink():
        return requested.resolve() if requested.is_symlink() else requested, None
    # A file requested beneath an already-created dataset symlink is already
    # addressed on SSD; creating a second alias for that child would collide.
    resolved_parent = requested.parent.resolve()
    if resolved_parent != requested.parent.absolute():
        return resolved_parent / requested.name, None
    parts = requested.parts
    if parts and parts[0] == "dataset":
        return SSD_ROOT.joinpath(*parts[1:]), requested
    return requested, None


def link_alias(target: Path, alias: Path | None) -> None:
    if alias is None:
        return
    alias.parent.mkdir(parents=True, exist_ok=True)
    if alias.is_symlink() and alias.resolve() == target.resolve():
        return
    if alias.is_dir() and target.is_dir():
        # A prior child output may have created the alias directory. Expose all
        # generated children without replacing that directory or its contents.
        for child in sorted(target.iterdir(), key=lambda value: value.name):
            child_alias = alias / child.name
            if not child_alias.exists() and not child_alias.is_symlink():
                child_alias.symlink_to(child.resolve(), target_is_directory=child.is_dir())
        return
    if alias.exists() or alias.is_symlink():
        raise FileExistsError(f"Refusing to replace existing path: {alias}")
    alias.symlink_to(target.resolve(), target_is_directory=target.is_dir())
