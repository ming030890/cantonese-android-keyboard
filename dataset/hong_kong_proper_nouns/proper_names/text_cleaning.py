"""Minimal Unicode cleaning shared by graph construction and ranking."""

from __future__ import annotations

import re
import unicodedata


_WHITESPACE = re.compile(r"\s+")


def _drop_punctuation(value: str) -> str:
    # Replace punctuation with a boundary so English words cannot be joined.
    return "".join(" " if unicodedata.category(char).startswith("P") else char for char in value)


def clean_title(title: str) -> str:
    """Drop punctuation and normalize whitespace without case conversion."""
    return _WHITESPACE.sub(" ", _drop_punctuation(str(title))).strip()


def clean_text_for_matching(text: str) -> str:
    """Prepare article text for matching while preserving script and casing."""
    return _WHITESPACE.sub(" ", _drop_punctuation(str(text))).strip()
