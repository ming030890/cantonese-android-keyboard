#!/usr/bin/env python3
"""
Build compact binary dictionaries for Cantonese Quick (速成) version 3 and version 5.

Binary format:
- 703 integers (4 bytes each, big-endian) representing byte offsets into the character pool.
- UTF-8 bytes of candidate characters.
Total codes: 702 (0..25 for 'a'..'z', 26..701 for 'aa'..'zz').
Candidates for code i start at offsets[i] and end at offsets[i+1].
"""

import json
import os
import struct
import urllib.request
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
KEYBOARD_DIR = ROOT / "cantonese-voice-keyboard"
ASSETS_DIR = KEYBOARD_DIR / "app/src/main/assets/quick"
RAW_DATA_DIR = Path("/Volumes/SSD/quick3_streaming_data/raw")
FREQ_PATH = ROOT / "dataset/quick3-streaming/runs/wordshk-frequency-source/charcount.json"

def code_to_index(code: str) -> int:
    code = code.lower()
    if len(code) == 1 and 'a' <= code[0] <= 'z':
        return ord(code[0]) - ord('a')
    elif len(code) == 2 and 'a' <= code[0] <= 'z' and 'a' <= code[1] <= 'z':
        return 26 + (ord(code[0]) - ord('a')) * 26 + (ord(code[1]) - ord('a'))
    return -1

def parse_dict_lines(lines, char_first=False):
    """
    Parses RIME yaml dictionary lines into a map of quick_code -> set of characters.
    If char_first is True: format is <char>\t<code>
    If char_first is False: format is <code>\t<char>
    """
    code_map = defaultdict(set)
    active = False
    for line in lines:
        line = line.strip()
        if line == '...':
            active = True
            continue
        if not active or line.startswith('#'):
            continue
        parts = line.split('\t')
        if len(parts) >= 2:
            if char_first:
                char, code = parts[0], parts[1]
            else:
                code, char = parts[0], parts[1]
            if len(char) == 1 and code.isalpha():
                code = code.lower()
                qcode = code if len(code) == 1 else (code[0] + code[-1])
                code_map[qcode].add(char)
    return code_map

def load_frequencies():
    if FREQ_PATH.exists():
        print(f"Loading frequencies from {FREQ_PATH}")
        return json.loads(FREQ_PATH.read_text())
    print("Warning: Frequency file not found. Using uniform ranking.")
    return {}

def build_cj3():
    print("Building Quick 3 table...")
    lines_ms = (RAW_DATA_DIR / "cangjie.3-ms.dict.yaml").read_text(encoding="utf-8").splitlines()
    lines_base = (RAW_DATA_DIR / "cangjie.3-base.dict.yaml").read_text(encoding="utf-8").splitlines()

    # In CJ3 files: format is <code>\t<char>
    ms_map = parse_dict_lines(lines_ms, char_first=False)
    base_map = parse_dict_lines(lines_base, char_first=False)

    combined = defaultdict(set)
    for qcode, chars in ms_map.items():
        combined[qcode].update(chars)
    for qcode, chars in base_map.items():
        combined[qcode].update(chars)
    return combined

def build_cj5():
    print("Building Quick 5 table...")
    urls = [
        "https://raw.githubusercontent.com/rime/rime-cangjie/master/cangjie5.base.dict.yaml",
        "https://raw.githubusercontent.com/rime/rime-cangjie/master/cangjie5.extended.dict.yaml"
    ]
    cache_dir = ROOT / ".cache/cangjie5"
    cache_dir.mkdir(parents=True, exist_ok=True)

    combined = defaultdict(set)
    for url in urls:
        filename = Path(url).name
        local_path = cache_dir / filename
        if not local_path.exists():
            print(f"Downloading {url} to {local_path}...")
            req = urllib.request.urlopen(url)
            local_path.write_bytes(req.read())
        lines = local_path.read_text(encoding="utf-8").splitlines()
        # In CJ5 files: format is <char>\t<code>
        table_map = parse_dict_lines(lines, char_first=True)
        for qcode, chars in table_map.items():
            combined[qcode].update(chars)
    return combined

def serialize_table(code_map, frequencies, out_path: Path):
    out_path.parent.mkdir(parents=True, exist_ok=True)
    # Order candidates for each code by frequency descending
    char_pool_bytes = bytearray()
    offsets = [0] * 703

    total_chars = 0
    for idx in range(702):
        offsets[idx] = len(char_pool_bytes)
        # Determine code string from index
        if idx < 26:
            code = chr(ord('a') + idx)
        else:
            c0 = chr(ord('a') + (idx - 26) // 26)
            c1 = chr(ord('a') + (idx - 26) % 26)
            code = c0 + c1

        chars = list(code_map.get(code, []))
        if chars:
            # Sort by frequency descending, then unicode order
            chars.sort(key=lambda c: (-frequencies.get(c, 0), c))
            total_chars += len(chars)
            for c in chars:
                char_pool_bytes.extend(c.encode("utf-8"))

    offsets[702] = len(char_pool_bytes)

    # Pack into binary file
    with open(out_path, "wb") as f:
        # Write 703 32-bit big-endian integers
        for off in offsets:
            f.write(struct.pack(">I", off))
        f.write(char_pool_bytes)

    file_size_kb = out_path.stat().st_size / 1024
    print(f"Saved {out_path} ({file_size_kb:.1f} KB, {total_chars} characters)")

def main():
    frequencies = load_frequencies()
    cj3_table = build_cj3()
    cj5_table = build_cj5()

    serialize_table(cj3_table, frequencies, ASSETS_DIR / "quick3.bin")
    serialize_table(cj5_table, frequencies, ASSETS_DIR / "quick5.bin")
    print("Done generating Quick binary assets!")

if __name__ == "__main__":
    main()
