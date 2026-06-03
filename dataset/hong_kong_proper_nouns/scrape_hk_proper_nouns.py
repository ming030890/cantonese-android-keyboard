#!/usr/bin/env python3
"""Build a Hong Kong proper-noun dataset from public knowledge sources.

The script intentionally uses only the Python standard library so it can run in
this Android project without adding a Python dependency manager.
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
import time
from collections import Counter, deque
from dataclasses import asdict, dataclass
from functools import lru_cache
from pathlib import Path
from typing import Iterable
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parent
DEFAULT_OUTPUT_DIR = ROOT / "out"
SEED_TERMS = ROOT / "seed_terms.tsv"
MOBILE_TERMS = ROOT / "mobile_terms.tsv"
USER_AGENT = "cantonese-voice-keyboard-hk-proper-nouns/1.0 (dataset builder)"
HF_DATASET_ROWS_URL = "https://datasets-server.huggingface.co/rows"
DEFAULT_HF_DATASET = "ming030890/wiki-yue-high-quality"

HK_SIGNALS = (
    "香港",
    "港人",
    "港島",
    "九龍",
    "新界",
    "港鐵",
    "中環",
    "灣仔",
    "銅鑼灣",
    "尖沙咀",
    "旺角",
    "深水埗",
    "觀塘",
    "荃灣",
    "沙田",
    "大埔",
    "元朗",
    "屯門",
    "將軍澳",
)


@dataclass(frozen=True)
class Record:
    term: str
    category: str
    lang: str
    source: str
    source_url: str


def normalize_term(value: str) -> str:
    value = re.sub(r"\s+", " ", value).strip()
    value = value.strip("[]()（）「」『』,，.。:：;；")
    return value


def dedupe_key(record: Record) -> tuple[str, str]:
    return (record.term.casefold(), record.category)


def fetch_json(url: str, params: dict[str, str | int], retries: int = 3, max_retry_after: int = 20) -> dict:
    query_url = f"{url}?{urlencode(params)}"
    request = Request(query_url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
    last_error: Exception | None = None
    for attempt in range(retries):
        try:
            with urlopen(request, timeout=45) as response:
                return json.loads(response.read().decode("utf-8"))
        except (HTTPError, URLError, TimeoutError) as exc:
            last_error = exc
            if isinstance(exc, HTTPError) and exc.code in {400, 404}:
                break
            retry_after = exc.headers.get("Retry-After") if isinstance(exc, HTTPError) else None
            if retry_after and retry_after.isdigit():
                wait_seconds = int(retry_after)
                if wait_seconds > max_retry_after:
                    break
                time.sleep(wait_seconds)
            else:
                time.sleep(2**attempt)
    raise RuntimeError(f"Failed to fetch {query_url}: {last_error}")


def load_tsv_terms(path: Path, default_source: str) -> list[Record]:
    if not path.exists():
        return []
    with path.open("r", encoding="utf-8", newline="") as handle:
        return [
            Record(
                term=normalize_term(row["term"]),
                category=row["category"],
                lang=row.get("lang", ""),
                source=row.get("source", default_source),
                source_url=row.get("source_url", ""),
            )
            for row in csv.DictReader(handle, delimiter="\t")
            if normalize_term(row.get("term", ""))
        ]


def load_curated_terms() -> list[Record]:
    records: list[Record] = []
    records.extend(load_tsv_terms(SEED_TERMS, "seed"))
    records.extend(load_tsv_terms(MOBILE_TERMS, "mobile_seed"))
    return records


@lru_cache(maxsize=1)
def curated_category_terms() -> tuple[tuple[str, str], ...]:
    records = load_curated_terms()
    pairs = [(record.term, record.category) for record in records if record.category not in {"seed"}]
    return tuple(sorted(pairs, key=lambda item: len(item[0]), reverse=True))


def infer_category_from_curated_terms(title: str) -> str | None:
    title_folded = title.casefold()
    for term, category in curated_category_terms():
        if title_folded == term.casefold():
            return category
    for term, category in curated_category_terms():
        if len(term) >= 3 and term.casefold() in title_folded:
            return category
    return None


def wikidata_query(category: str, sparql: str, limit: int) -> list[Record]:
    endpoint = "https://query.wikidata.org/sparql"
    data = fetch_json(endpoint, {"query": sparql, "format": "json", "limit": limit})
    records: list[Record] = []
    for binding in data.get("results", {}).get("bindings", []):
        entity_url = binding.get("item", {}).get("value", "")
        for field, lang in (
            ("itemLabel", "auto"),
            ("zhLabel", "zh"),
            ("zhHantLabel", "zh-Hant"),
            ("zhHKLabel", "zh-HK"),
            ("enLabel", "en"),
        ):
            term = normalize_term(binding.get(field, {}).get("value", ""))
            if not term or term.startswith("Q"):
                continue
            records.append(Record(term, category, lang, "wikidata", entity_url))
    return records


def wikidata_records(limit: int) -> list[Record]:
    # Q8646 is Hong Kong. These queries favor traceable structured data over
    # broad text scraping, then emit available Chinese and English labels.
    queries = {
        "place": f"""
            SELECT ?item ?itemLabel ?zhLabel ?zhHantLabel ?zhHKLabel ?enLabel WHERE {{
              ?item wdt:P131* wd:Q8646.
              OPTIONAL {{ ?item rdfs:label ?zhLabel FILTER(LANG(?zhLabel) = "zh") }}
              OPTIONAL {{ ?item rdfs:label ?zhHantLabel FILTER(LANG(?zhHantLabel) = "zh-hant") }}
              OPTIONAL {{ ?item rdfs:label ?zhHKLabel FILTER(LANG(?zhHKLabel) = "zh-hk") }}
              OPTIONAL {{ ?item rdfs:label ?enLabel FILTER(LANG(?enLabel) = "en") }}
              SERVICE wikibase:label {{ bd:serviceParam wikibase:language "zh-hk,zh-hant,zh,en". }}
            }}
            LIMIT {limit}
        """,
        "celebrity": f"""
            SELECT ?item ?itemLabel ?zhLabel ?zhHantLabel ?zhHKLabel ?enLabel WHERE {{
              ?item wdt:P31 wd:Q5.
              {{
                ?item wdt:P27 wd:Q8646.
              }} UNION {{
                ?item wdt:P19/wdt:P131* wd:Q8646.
              }} UNION {{
                ?item wdt:P551/wdt:P131* wd:Q8646.
              }}
              OPTIONAL {{ ?item rdfs:label ?zhLabel FILTER(LANG(?zhLabel) = "zh") }}
              OPTIONAL {{ ?item rdfs:label ?zhHantLabel FILTER(LANG(?zhHantLabel) = "zh-hant") }}
              OPTIONAL {{ ?item rdfs:label ?zhHKLabel FILTER(LANG(?zhHKLabel) = "zh-hk") }}
              OPTIONAL {{ ?item rdfs:label ?enLabel FILTER(LANG(?enLabel) = "en") }}
              SERVICE wikibase:label {{ bd:serviceParam wikibase:language "zh-hk,zh-hant,zh,en". }}
            }}
            LIMIT {limit}
        """,
        "organization": f"""
            SELECT ?item ?itemLabel ?zhLabel ?zhHantLabel ?zhHKLabel ?enLabel WHERE {{
              {{
                ?item wdt:P159/wdt:P131* wd:Q8646.
              }} UNION {{
                ?item wdt:P740/wdt:P131* wd:Q8646.
              }} UNION {{
                ?item wdt:P17 wd:Q8646.
              }}
              OPTIONAL {{ ?item rdfs:label ?zhLabel FILTER(LANG(?zhLabel) = "zh") }}
              OPTIONAL {{ ?item rdfs:label ?zhHantLabel FILTER(LANG(?zhHantLabel) = "zh-hant") }}
              OPTIONAL {{ ?item rdfs:label ?zhHKLabel FILTER(LANG(?zhHKLabel) = "zh-hk") }}
              OPTIONAL {{ ?item rdfs:label ?enLabel FILTER(LANG(?enLabel) = "en") }}
              SERVICE wikibase:label {{ bd:serviceParam wikibase:language "zh-hk,zh-hant,zh,en". }}
            }}
            LIMIT {limit}
        """,
    }

    records: list[Record] = []
    for category, query in queries.items():
        records.extend(wikidata_query(category, query, limit))
        time.sleep(0.5)
    return records


def wikipedia_category_records(
    api_url: str,
    category: str,
    output_category: str,
    limit: int,
    max_depth: int,
    sleep_seconds: float,
) -> list[Record]:
    records: list[Record] = []
    seen_categories = {category}
    queue: deque[tuple[str, int]] = deque([(category, 0)])

    while queue and len(records) < limit:
        current_category, depth = queue.popleft()
        params: dict[str, str | int] = {
            "action": "query",
            "format": "json",
            "list": "categorymembers",
            "cmtitle": current_category,
            "cmlimit": 500,
            "cmnamespace": "0|14",
        }
        while len(records) < limit:
            data = fetch_json(api_url, params)
            for item in data.get("query", {}).get("categorymembers", []):
                title = normalize_term(item.get("title", ""))
                namespace = item.get("ns")
                if namespace == 14:
                    if depth < max_depth and title not in seen_categories:
                        seen_categories.add(title)
                        queue.append((title, depth + 1))
                    continue
                if title:
                    records.append(Record(title, output_category, "wiki", "wikipedia", f"{api_url}?curid={item.get('pageid', '')}"))
                if len(records) >= limit:
                    break
            cont = data.get("continue", {}).get("cmcontinue")
            if not cont:
                break
            params["cmcontinue"] = cont
            time.sleep(sleep_seconds)
        time.sleep(sleep_seconds)
    return records


def wikipedia_records(limit: int, max_depth: int, sleep_seconds: float) -> list[Record]:
    categories = [
        ("https://zh-yue.wikipedia.org/w/api.php", "Category:香港地方", "place"),
        ("https://zh-yue.wikipedia.org/w/api.php", "Category:香港建築", "place"),
        ("https://zh-yue.wikipedia.org/w/api.php", "Category:香港交通", "place"),
        ("https://zh-yue.wikipedia.org/w/api.php", "Category:香港人", "celebrity"),
        ("https://zh-yue.wikipedia.org/w/api.php", "Category:香港歌手", "celebrity"),
        ("https://zh-yue.wikipedia.org/w/api.php", "Category:香港演員", "celebrity"),
        ("https://zh-yue.wikipedia.org/w/api.php", "Category:香港公司", "organization"),
        ("https://zh-yue.wikipedia.org/w/api.php", "Category:香港學校", "organization"),
        ("https://zh.wikipedia.org/w/api.php", "Category:香港地名", "place"),
        ("https://zh.wikipedia.org/w/api.php", "Category:香港街道", "place"),
        ("https://zh.wikipedia.org/w/api.php", "Category:香港鐵路車站", "place"),
        ("https://zh.wikipedia.org/w/api.php", "Category:香港商場", "place"),
        ("https://zh.wikipedia.org/w/api.php", "Category:香港公共屋邨", "place"),
        ("https://zh.wikipedia.org/w/api.php", "Category:香港旅遊景點", "place"),
        ("https://zh.wikipedia.org/w/api.php", "Category:香港人", "celebrity"),
        ("https://zh.wikipedia.org/w/api.php", "Category:香港男演員", "celebrity"),
        ("https://zh.wikipedia.org/w/api.php", "Category:香港女演員", "celebrity"),
        ("https://zh.wikipedia.org/w/api.php", "Category:香港男歌手", "celebrity"),
        ("https://zh.wikipedia.org/w/api.php", "Category:香港女歌手", "celebrity"),
        ("https://zh.wikipedia.org/w/api.php", "Category:香港政治人物", "celebrity"),
        ("https://zh.wikipedia.org/w/api.php", "Category:香港運動員", "celebrity"),
        ("https://zh.wikipedia.org/w/api.php", "Category:香港公司", "organization"),
        ("https://zh.wikipedia.org/w/api.php", "Category:香港學校", "organization"),
        ("https://zh.wikipedia.org/w/api.php", "Category:香港大專院校", "organization"),
        ("https://zh.wikipedia.org/w/api.php", "Category:香港政府部門", "organization"),
        ("https://en.wikipedia.org/w/api.php", "Category:Places in Hong Kong", "place"),
        ("https://en.wikipedia.org/w/api.php", "Category:MTR stations", "place"),
        ("https://en.wikipedia.org/w/api.php", "Category:Shopping centres in Hong Kong", "place"),
        ("https://en.wikipedia.org/w/api.php", "Category:Public housing estates in Hong Kong", "place"),
        ("https://en.wikipedia.org/w/api.php", "Category:Hong Kong actors", "celebrity"),
        ("https://en.wikipedia.org/w/api.php", "Category:Hong Kong singers", "celebrity"),
        ("https://en.wikipedia.org/w/api.php", "Category:Hong Kong politicians", "celebrity"),
        ("https://en.wikipedia.org/w/api.php", "Category:Hong Kong sportspeople", "celebrity"),
        ("https://en.wikipedia.org/w/api.php", "Category:Companies of Hong Kong", "organization"),
        ("https://en.wikipedia.org/w/api.php", "Category:Schools in Hong Kong", "organization"),
        ("https://en.wikipedia.org/w/api.php", "Category:Universities in Hong Kong", "organization"),
    ]
    records: list[Record] = []
    per_category_limit = max(50, limit)
    for api_url, category, output_category in categories:
        try:
            records.extend(wikipedia_category_records(api_url, category, output_category, per_category_limit, max_depth, sleep_seconds))
        except RuntimeError as exc:
            print(f"warning: skipped {category}: {exc}", file=sys.stderr)
        time.sleep(sleep_seconds)
    return records


def contains_any(value: str, needles: tuple[str, ...]) -> bool:
    return any(needle in value for needle in needles)


def infer_category_from_article(title: str, text: str) -> str:
    curated_category = infer_category_from_curated_terms(title)
    if curated_category:
        return curated_category

    # For title extraction, category inference should mostly classify the title
    # itself. Using the full article body creates false positives whenever an
    # unrelated page merely mentions a bank, app, school, or government office.
    category_rules: tuple[tuple[str, tuple[str, ...]], ...] = (
        ("transport", ("港鐵", "車站", "鐵路站", "巴士", "小巴", "渡輪", "碼頭", "隧道", "機場快綫", "輕鐵", "電車", "纜車", "交通交匯處", "巴士總站", "公共運輸")),
        ("hospital", ("醫院", "診所", "醫療中心", "醫務中心", "急症室", "健康院", "醫療機構")),
        ("school", ("大學", "中學", "小學", "書院", "幼稚園", "學校", "學院", "專上學院", "教育大學", "理工大學", "浸會大學")),
        ("finance", ("銀行", "證券", "保險", "基金", "交易所", "金融", "恒生指數", "虛擬銀行", "按揭", "信用卡")),
        ("mall", ("商場", "購物中心", "廣場", "中心商場", "百貨", "街市", "市集")),
        ("residential", ("屋邨", "邨", "屋苑", "花園", "苑", "居", "大廈", "豪庭", "山莊", "半島", "灣畔", "新村", "村")),
        ("food_brand", ("餐廳", "茶餐廳", "酒樓", "快餐", "咖啡店", "咖啡室", "麵家", "冰室", "餅店", "食品", "飲食", "米線", "壽司")),
        ("shop_brand", ("超級市場", "便利店", "零售", "百貨", "藥房", "藥行", "電器", "家具", "時裝", "服飾", "連鎖店")),
        ("media", ("電視台", "電台", "報章", "報紙", "雜誌", "新聞", "傳媒", "討論區", "網媒", "電影公司", "唱片公司")),
        ("event", ("展覽", "博覽", "音樂節", "藝術節", "電影節", "書展", "馬拉松", "節日", "嘉年華", "巡遊", "演唱會")),
        ("app", ("手機應用", "流動應用", "應用程式", "支付應用", "即時通訊軟件", "流動支付")),
        ("government", ("政府部門", "政府機構", "公營機構", "法定機構", "管理局", "委員會", "公署", "立法會", "區議會")),
    )
    for category, needles in category_rules:
        if contains_any(title, needles):
            return category

    intro = f"{title}\n{text[:500]}"
    if contains_any(intro, ("演員", "歌手", "藝人", "導演", "主持", "政治人物", "運動員", "網紅", "YouTuber", "作家", "填詞人", "作曲家")):
        return "celebrity"

    if contains_any(title, ("公司", "機構", "組織", "協會", "基金會", "學會", "商會", "電視", "報章")):
        return "organization"
    if contains_any(title, ("街", "道", "區", "島", "山", "灣", "站", "公園", "海灘", "泳灘", "廣場", "大樓")):
        return "place"
    return "wiki_title"


def looks_hong_kong_related(title: str, text: str) -> bool:
    haystack = f"{title}\n{text[:1200]}"
    return any(signal in haystack for signal in HK_SIGNALS)


def looks_like_proper_title(title: str) -> bool:
    if len(title) < 2:
        return False
    if re.fullmatch(r"[\d０-９]+", title):
        return False
    if re.fullmatch(r"[\d０-９]+月[\d０-９]+號?", title):
        return False
    if re.fullmatch(r"[\d０-９]+年", title):
        return False
    if re.fullmatch(r"[\d０-９]+年代", title):
        return False
    if re.fullmatch(r"[\d０-９]+世紀", title):
        return False
    if re.fullmatch(r"[\W_+=\-.$]+", title):
        return False
    if any(marker in title for marker in ("一覽", "列表", "名單", "時間表", "年表")):
        return False
    return True


def digit_count(value: str) -> int:
    return sum(1 for char in value if char.isdigit() or "０" <= char <= "９")


def looks_like_clean_proper_noun(title: str) -> bool:
    if not looks_like_proper_title(title):
        return False
    digits = digit_count(title)
    if digits:
        return False
    noisy_patterns = (
        r".*[\d０-９]{4}年.*",
        r".*[\d０-９]+[/-][\d０-９]+.*",
        r".*[\d０-９]+月.*",
        r".*[\d０-９]+日.*",
        r".*[\d０-９]+年度.*",
        r".*第[\d０-９一二三四五六七八九十百]+屆.*",
    )
    if any(re.fullmatch(pattern, title) for pattern in noisy_patterns):
        return False
    noisy_terms = (
        "事件",
        "事故",
        "風波",
        "爭議",
        "衝突",
        "示威",
        "遊行",
        "選舉",
        "補選",
        "公投",
        "競選",
        "賽季",
        "球季",
        "聯賽",
        "錦標賽",
        "運動會",
        "代表團",
        "颱風",
        "疫情",
        "人口普查",
        "施政報告",
        "計劃",
    )
    if any(term in title for term in noisy_terms):
        return False
    return True


def huggingface_title_records(
    dataset: str,
    config: str,
    split: str,
    scan_rows: int,
    limit: int,
    hk_filter: bool,
    sleep_seconds: float,
) -> list[Record]:
    records: list[Record] = []
    offset = 0
    page_size = 100
    source_url = f"https://huggingface.co/datasets/{dataset}"

    while offset < scan_rows and len(records) < limit:
        length = min(page_size, scan_rows - offset)
        try:
            data = fetch_json(
                HF_DATASET_ROWS_URL,
                {
                    "dataset": dataset,
                    "config": config,
                    "split": split,
                    "offset": offset,
                    "length": length,
                },
            )
        except RuntimeError as exc:
            print(f"warning: stopped Hugging Face scan at offset {offset}: {exc}", file=sys.stderr)
            break
        rows = data.get("rows", [])
        if not rows:
            break
        for item in rows:
            row = item.get("row", {})
            title = normalize_term(str(row.get("title", "")))
            text = str(row.get("text", ""))
            if not title or not looks_like_proper_title(title):
                continue
            if hk_filter and not looks_hong_kong_related(title, text):
                continue
            records.append(
                Record(
                    term=title,
                    category=infer_category_from_article(title, text),
                    lang="zh-yue",
                    source="huggingface",
                    source_url=source_url,
                )
            )
            if len(records) >= limit:
                break
        if len(rows) < length:
            break
        offset += length
        time.sleep(sleep_seconds)

    return records


def local_parquet_title_records(parquet_path: Path, limit: int, hk_filter: bool) -> list[Record]:
    try:
        import pyarrow.parquet as pq
    except ImportError as exc:
        raise RuntimeError("local parquet extraction requires pyarrow; run with the conda Python that has pyarrow installed") from exc

    records: list[Record] = []
    source_url = str(parquet_path)
    parquet_file = pq.ParquetFile(parquet_path)
    for batch in parquet_file.iter_batches(columns=["title", "text"], batch_size=2048):
        titles = batch.column("title").to_pylist()
        texts = batch.column("text").to_pylist()
        for title_value, text_value in zip(titles, texts):
            title = normalize_term(str(title_value or ""))
            text = str(text_value or "")
            if not title or not looks_like_proper_title(title):
                continue
            if hk_filter and not looks_hong_kong_related(title, text):
                continue
            records.append(
                Record(
                    term=title,
                    category=infer_category_from_article(title, text),
                    lang="zh-yue",
                    source="local_parquet",
                    source_url=source_url,
                )
            )
            if len(records) >= limit:
                return records
    return records


def dedupe(records: Iterable[Record]) -> list[Record]:
    seen: set[tuple[str, str]] = set()
    clean: list[Record] = []
    for record in records:
        term = normalize_term(record.term)
        if not term or len(term) > 80:
            continue
        candidate = Record(term, record.category, record.lang, record.source, record.source_url)
        key = dedupe_key(candidate)
        if key in seen:
            continue
        seen.add(key)
        clean.append(candidate)
    return sorted(clean, key=lambda item: (item.category, item.term.casefold(), item.source))


def post_filter(records: Iterable[Record], mode: str) -> list[Record]:
    if mode == "none":
        return list(records)
    if mode == "clean":
        return [record for record in records if looks_like_clean_proper_noun(record.term)]
    raise ValueError(f"Unknown post-filter mode: {mode}")


def write_outputs(records: list[Record], output_dir: Path) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)

    jsonl_path = output_dir / "hk_proper_nouns.jsonl"
    with jsonl_path.open("w", encoding="utf-8") as handle:
        for record in records:
            handle.write(json.dumps(asdict(record), ensure_ascii=False, sort_keys=True) + "\n")

    tsv_path = output_dir / "hk_proper_nouns.tsv"
    with tsv_path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=["term", "category", "lang", "source", "source_url"], delimiter="\t")
        writer.writeheader()
        for record in records:
            writer.writerow(asdict(record))

    terms_path = output_dir / "terms.txt"
    unique_terms = sorted({record.term for record in records}, key=str.casefold)
    terms_path.write_text("\n".join(unique_terms) + "\n", encoding="utf-8")

    stats = {
        "records": len(records),
        "unique_terms": len(unique_terms),
        "by_category": Counter(record.category for record in records),
        "by_source": Counter(record.source for record in records),
    }
    stats_path = output_dir / "stats.json"
    stats_path.write_text(json.dumps(stats, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Scrape Hong Kong proper nouns into JSONL, TSV, and term-list outputs.")
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR, help="Directory for generated files.")
    parser.add_argument("--limit", type=int, default=500, help="Maximum records to fetch per configured source category/query.")
    parser.add_argument(
        "--sources",
        default="huggingface",
        help="Comma-separated sources: seed,local-parquet,huggingface,wikipedia,wikidata,all. Default: huggingface.",
    )
    parser.add_argument("--include-wikipedia", action="store_true", help="Deprecated alias for --sources wikipedia,wikidata.")
    parser.add_argument("--category-depth", type=int, default=1, help="Wikipedia subcategory traversal depth.")
    parser.add_argument("--sleep", type=float, default=1.0, help="Seconds to sleep between public API requests.")
    parser.add_argument("--hf-dataset", default=DEFAULT_HF_DATASET, help="Hugging Face dataset id for title extraction.")
    parser.add_argument("--hf-config", default="default", help="Hugging Face dataset config.")
    parser.add_argument("--hf-split", default="train", help="Hugging Face dataset split.")
    parser.add_argument("--hf-scan-rows", type=int, default=20000, help="Maximum Hugging Face rows to scan.")
    parser.add_argument("--local-parquet", type=Path, help="Local parquet file with title/text columns, usually downloaded with `hf download`.")
    parser.add_argument("--no-hk-filter", action="store_true", help="Keep all Hugging Face article titles instead of filtering for Hong Kong signals.")
    parser.add_argument("--post-filter", choices=["none", "clean"], default="none", help="Optional title cleanup after collection.")
    parser.add_argument("--seed-only", action="store_true", help="Only normalize seed_terms.tsv; do not call network sources.")
    parser.add_argument("--strict", action="store_true", help="Exit non-zero if any network source fails.")
    return parser.parse_args(argv)


def selected_sources(args: argparse.Namespace) -> set[str]:
    if args.seed_only:
        return {"seed"}
    if args.include_wikipedia:
        return {"seed", "wikipedia", "wikidata"}
    sources = {source.strip().lower() for source in args.sources.split(",") if source.strip()}
    if "all" in sources:
        return {"seed", "local-parquet", "huggingface", "wikipedia", "wikidata"}
    sources.add("seed")
    allowed = {"seed", "local-parquet", "huggingface", "wikipedia", "wikidata"}
    unknown = sources - allowed
    if unknown:
        raise SystemExit(f"Unknown source(s): {', '.join(sorted(unknown))}")
    return sources


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    sources = selected_sources(args)
    records = load_curated_terms() if "seed" in sources else []
    errors: list[str] = []

    if "wikipedia" in sources:
        try:
            records.extend(wikipedia_records(args.limit, args.category_depth, args.sleep))
        except RuntimeError as exc:
            errors.append(f"wikipedia: {exc}")
    if "local-parquet" in sources:
        if not args.local_parquet:
            errors.append("local-parquet: --local-parquet is required")
        else:
            try:
                records.extend(local_parquet_title_records(args.local_parquet, args.limit, not args.no_hk_filter))
            except RuntimeError as exc:
                errors.append(f"local-parquet: {exc}")
    if "huggingface" in sources:
        try:
            records.extend(
                huggingface_title_records(
                    dataset=args.hf_dataset,
                    config=args.hf_config,
                    split=args.hf_split,
                    scan_rows=args.hf_scan_rows,
                    limit=args.limit,
                    hk_filter=not args.no_hk_filter,
                    sleep_seconds=args.sleep,
                )
            )
        except RuntimeError as exc:
            errors.append(f"huggingface: {exc}")
    if "wikidata" in sources:
        try:
            records.extend(wikidata_records(args.limit))
        except RuntimeError as exc:
            errors.append(f"wikidata: {exc}")

    clean = dedupe(post_filter(records, args.post_filter))
    write_outputs(clean, args.output_dir)
    print(f"Wrote {len(clean)} records to {args.output_dir}")
    for error in errors:
        print(f"warning: {error}", file=sys.stderr)
    if errors and args.strict:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
