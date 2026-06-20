"""Conservative filters for titles likely to be spoken proper names."""

from __future__ import annotations

import re
from collections.abc import Collection

from .text_cleaning import clean_title


_BAD_PREFIXES = (
    "Category:", "分類:", "Template:", "模:", "File:", "Image:", "檔案:",
    "User:", "用戶:", "Wikipedia:", "維基百科:", "Module:", "模組:",
    "Portal:", "Special:", "Talk:", "Help:", "Draft:",
)
_BAD_PHRASES = (
    "搞清楚", "消歧義", "消歧義頁", "一覽", "列表", "年表", "事件列表",
    "人物列表", "作品列表", "行政區劃", "歷史", "文化", "社會", "政治",
    "經濟", "科學", "哲學", "概念", "理論", "方法", "問題", "現象",
)
_GENERIC_TITLES = {
    "巴士站", "巴士總站", "火車站", "地鐵站", "鐵路站", "特殊學校", "學校",
    "大學", "醫院", "商場", "夜總會", "公司", "組織", "機構", "政黨", "車站",
    "機場", "碼頭", "屋邨", "屋苑", "人物", "歌手", "演員", "電影", "電視台",
    "報紙", "雜誌", "銀行", "公園", "博物館", "圖書館", "體育館", "行政區",
    "委員會", "有限公司", "特別行政區", "公共屋邨", "居者有其屋", "選舉委員會",
    "學生會", "子公司", "董事會", "政治局", "上市公司", "公共圖書館", "消防局",
    "海底隧道", "通道", "新聞從業員", "商務中心", "公共交通", "公共機構",
}
_DATE_OR_LIST = re.compile(r"^(?:\d{1,4}年|\d+世紀|List of\b)", re.IGNORECASE)
_NOISY = re.compile(r"(?:https?://|www\.|\{\{|\}\}|\[\[|\]\]|\n)")
_ALNUM = re.compile(r"[A-Za-z0-9\u3400-\u9fff]")


def is_candidate_title(title: str, seeds: Collection[str] | None = None) -> bool:
    raw = str(title).strip()
    cleaned = clean_title(raw)
    clean_seeds = seeds or ()
    if cleaned in clean_seeds:
        return True
    if not cleaned or len(cleaned) < 2 or len(cleaned) > 30:
        return False
    if raw.startswith(_BAD_PREFIXES) or _NOISY.search(raw):
        return False
    if "(" in raw or "（" in raw or raw.endswith("搞清楚"):
        return False
    if cleaned in _GENERIC_TITLES:
        return False
    if _DATE_OR_LIST.search(cleaned) or any(phrase == cleaned or cleaned.endswith(phrase) for phrase in _BAD_PHRASES):
        return False
    if not _ALNUM.search(cleaned):
        return False
    if sum(char.isdigit() for char in cleaned) > max(2, len(cleaned) // 3):
        return False
    return True
