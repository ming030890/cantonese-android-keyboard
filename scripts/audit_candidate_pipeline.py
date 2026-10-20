#!/usr/bin/env python3
"""Frozen diagnostic ablations on SSD mobile events and Quick development data.

No training, threshold search, model-vocabulary filtering of labels, or protected
test access. Aalto AC events are a selected event population, not all keystrokes.
Sentence IDs group prompts; TEST_SECTION_ID is deliberately not called a person.
"""
import argparse
import collections
import csv
import hashlib
import json
import math
import re
import statistics
import struct
import subprocess
from pathlib import Path

WORD = re.compile(r"[A-Za-z]+(?:'[A-Za-z]+)*\Z")
SEED = "occam-pipeline-20261020-v1"


def digest(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def key(value):
    return hashlib.sha256((SEED + json.dumps(value, ensure_ascii=False, sort_keys=True)).encode()).hexdigest()


def preceding_context(current, typed):
    """Recover observable text before composition; never use reference/AC_WORD."""
    current = current.rstrip()
    if not typed or not current.lower().endswith(typed.lower()):
        return None
    left = current[:-len(typed)]
    if left and (left[-1].isascii() and (left[-1].isalnum() or left[-1] == "'")):
        return None
    return left


def mobile_cases(raw, count, reserved=False):
    buckets = collections.defaultdict(list)
    audit = collections.Counter()
    events = raw / "ac_words_en.csv"
    with events.open(newline="", encoding="utf-8", errors="strict") as stream:
        for r in csv.DictReader(stream):
            audit["source_events"] += 1
            typed, target = r["TYPED_WORD"].strip(), r["ORIGINAL_WORD"].strip()
            if not WORD.fullmatch(typed) or not WORD.fullmatch(target) or not (2 <= len(typed) <= 32 and 2 <= len(target) <= 32):
                audit["excluded_unsupported_tokens"] += 1
                continue
            left = preceding_context(r["CURRENT_INPUT"], typed)
            if left is None:
                audit["excluded_ambiguous_current_suffix"] += 1
                continue
            group = r["SENTENCE_ID"]
            if (int(key(group)[:8], 16) % 5 == 0) != reserved:
                audit["unused_prompt_partition_events"] += 1
                continue
            row = dict(typed=typed, target=target.lower(), left=left,
                group=group, category="recorded_typo" if typed.lower() != target.lower() else "recorded_preservation")
            row["id"] = key(row)
            bucket = buckets[group]
            if any(v["id"] == row["id"] for v in bucket):
                continue
            bucket.append(row)
            bucket.sort(key=lambda v: v["id"])
            del bucket[8:]
    # Stable sample; cap per prompt limits repeated sentences dominating results.
    selected = sorted((r for b in buckets.values() for r in b), key=lambda r: r["id"])[:count]
    clean = []
    with (raw / "sentences.csv").open(newline="", encoding="utf-8") as stream:
        for r in csv.DictReader(stream):
            if (int(key(r["SENTENCE_ID"])[:8], 16) % 5 == 0) != reserved:
                continue
            for match in re.finditer(r"[A-Za-z]+(?:'[A-Za-z]+)*", r["SENTENCE"]):
                if not 2 <= len(match[0]) <= 32:
                    continue
                row = dict(typed=match[0], target=match[0].lower(), left=r["SENTENCE"][:match.start()],
                    group=r["SENTENCE_ID"], category="source_preservation")
                row["id"] = key(row)
                clean.append(row)
    selected += sorted(clean, key=lambda r: r["id"])[:count]
    audit["selected_events"] = sum(r["category"].startswith("recorded_") for r in selected)
    audit["selected_source_preservation"] = sum(r["category"] == "source_preservation" for r in selected)
    audit["prompt_groups"] = len({r["group"] for r in selected})
    return selected, dict(audit)


def quick_cases(path, per_source):
    sources = collections.defaultdict(dict)
    for line in path.open():
        r = json.loads(line)
        if not r["quick_codes"]:
            continue
        row = {k: r[k] for k in ("id", "source", "document_group", "target", "left", "right", "quick_codes")}
        group = r["document_group"]
        old = sources[r["source"]].get(group)
        if old is None or key(row["id"]) < key(old["id"]):
            sources[r["source"]][group] = row
    selected = []
    for source, groups in sorted(sources.items()):
        for i, row in enumerate(sorted(groups.values(), key=lambda r: key(r["id"]))[:per_source]):
            versions = sorted(row["quick_codes"])
            row["version"] = int(versions[i % len(versions)])
            row["code"] = row["quick_codes"][str(row["version"])]
            selected.append(row)
    return selected


def probe(binary, model, requests, extra=()):
    proc = subprocess.run([str(binary), str(model), *map(str, extra)],
        input="".join(json.dumps(r, ensure_ascii=False) + "\n" for r in requests),
        text=True, capture_output=True, check=True, timeout=600)
    outputs = [json.loads(line) for line in proc.stdout.splitlines()]
    if len(outputs) != len(requests):
        raise ValueError("Native request/result count mismatch")
    return outputs


def penalty(typed, word, edits):
    if edits == 0:
        return 0.
    positions = {c: (r, i) for r, row in enumerate(("qwertyuiop", "asdfghjkl", "zxcvbnm")) for i, c in enumerate(row)}
    def adjacent(a, b):
        return a != b and a in positions and b in positions and all(abs(x-y) <= 1 for x, y in zip(positions[a], positions[b]))
    delta = abs(len(typed) - len(word))
    if delta == 0:
        different = [(a, b) for a, b in zip(typed, word) if a != b]
        if len(different) == edits:
            cost = sum(1. if adjacent(a, b) else 2.5 for a, b in different)
        else:
            cost = edits + 2.5 * sum(b not in typed for b in word)
    else:
        cost = edits * 1.5 + delta * 3.
    return cost + max(0, edits - 1) * 3.


def choose_current(typed, left, ranked, known):
    """Mirror current native gates; exact parity is checked before any ablation."""
    if not ranked:
        return None
    b = ranked[0]
    n, edits = len(typed), b["edits"]
    delta = abs(n - len(b["word"]))
    if known or n < 3 or b["completedChars"] > 0 or len(b["word"]) < 3:
        return None
    if (edits >= 2 and delta > 0) or (n <= 3 and len(b["word"]) < n):
        return None
    if edits > (3 if n >= 6 else 2 if n >= 5 else 1) or b["score"] < -24.:
        return None
    margin = (.8 if edits == 1 else 1.2) if not left.strip() else (.5 if edits == 1 else .8)
    if len(ranked) > 1:
        runner = ranked[1]
        if runner["edits"] > edits:
            margin = 0.
        elif runner["edits"] == edits and runner["completedChars"] == 0 and abs(n - len(runner["word"])) > delta:
            margin *= .5
        if b["score"] - runner["score"] < margin:
            return None
    return b["word"]


def spelling_variants(case, out):
    typed = case["typed"].lower()
    rows = out["candidates"]
    expected = choose_current(typed, case["left"], rows, out["known"])
    if expected != out["correction"]:
        raise ValueError(f"Decision mirror differs from native for case {case['id']}")
    yield "current", rows, out["correction"]
    single = [dict(r) for r in rows if " " not in r["word"]]
    yield "without_split_rules", single, choose_current(typed, case["left"], single, out["known"])
    yield "without_known_word_veto", rows, choose_current(typed, case["left"], rows, False)
    uniform = []
    no_lm = []
    for r in single:
        p = r["completedChars"] * .5 if r["completedChars"] else penalty(typed, r["word"].lower(), r["edits"])
        logp = r["score"] + p
        cost = .5 * r["completedChars"] if r["completedChars"] else 2. * r["edits"]
        uniform.append(dict(r, score=logp-cost, logp=logp))
        no_lm.append(dict(r, score=-cost))
    for name, ranked in (("uniform_edit_cost", uniform), ("without_lm", no_lm)):
        ranked.sort(key=lambda r: (-r["score"], r["word"]))
        yield name, ranked, choose_current(typed, case["left"], ranked, out["known"])
    b = uniform[0] if uniform else None
    simple = None
    if b and not out["known"] and len(typed) >= 3 and b["edits"] == 1 and not b["completedChars"] and b["logp"] >= math.log(.0001):
        margin = 6. if not case["left"].strip() else 4.
        if len(uniform) < 2 or b["score"] - uniform[1]["score"] >= margin:
            simple = b["word"]
    yield "simple_one_edit_margin", uniform, simple


def spelling_metrics(cases, outputs):
    stats = collections.defaultdict(collections.Counter)
    for case, out in zip(cases, outputs):
        for variant, ranked, correction in spelling_variants(case, out):
            for cohort in ("all", case["category"]):
                s = stats[(variant, cohort)]
                typo = case["typed"].lower() != case["target"]
                s["cases"] += 1
                s["typos" if typo else "preservation"] += 1
                s["target_in_top8"] += int(case["target"] in [r["word"].lower() for r in ranked[:8]])
                if correction and correction.lower() != case["typed"].lower():
                    s["applied"] += 1
                    s["correct" if typo and correction.lower() == case["target"] else "wrong"] += 1
                    s["false_preservation_changes"] += int(not typo)
    result = {}
    for (variant, cohort), s in stats.items():
        result.setdefault(variant, {})[cohort] = dict(s,
            precision=s["correct"]/s["applied"] if s["applied"] else None,
            typo_recall=s["correct"]/s["typos"] if s["typos"] else None,
            preservation_change_rate=s["false_preservation_changes"]/s["preservation"] if s["preservation"] else None)
    return result


def dictionary(path):
    raw = path.read_bytes()
    offsets = struct.unpack(">703I", raw[:2812])
    pool = raw[2812:]
    return [list(pool[a:b].decode()) for a, b in zip(offsets, offsets[1:])]


def candidate_metrics(cases, full, preview, assets):
    tables = {v: dictionary(assets / f"quick/quick{v}.bin") for v in (3, 5)}
    stats = collections.defaultdict(collections.Counter)
    times = collections.defaultdict(list)
    for c, f, p in zip(cases, full, preview):
        code = c["code"].lower()
        index = 26 + (ord(code[0])-97)*26 + ord(code[1])-97
        baseline = tables[c["version"]][index]
        if sorted(baseline) != sorted(f["candidates"]) or sorted(baseline) != sorted(p["candidates"]):
            raise ValueError("Quick candidate set changed")
        for name, words in (("dictionary_only", baseline), ("preview", p["candidates"]), ("full", f["candidates"])):
            for cohort in ("all", c["source"], f"quick{c['version']}"):
                s = stats[(name, cohort)]
                s["cases"] += 1
                rank = words.index(c["target"]) + 1 if c["target"] in words else math.inf
                s["top1"] += rank == 1
                s["top3"] += rank <= 3
                s["top8"] += rank <= 8
                s["mrr"] += 1/rank
        for name, out in (("preview", p), ("full", f)):
            times[name].append(out["elapsedMicros"])
    result = {}
    for (variant, cohort), s in stats.items():
        result.setdefault(variant, {})[cohort] = dict(s,
            **{f"{k}_rate": s[k]/s["cases"] for k in ("top1", "top3", "top8", "mrr")})
    return result, {k: dict(median_us=statistics.median(v), p95_us=sorted(v)[int(.95*len(v))]) for k, v in times.items()}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--probe", type=Path, required=True)
    p.add_argument("--ssd", type=Path, default=Path("/Volumes/SSD/cantonese_ngram"))
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--mobile-count", type=int, default=1500)
    p.add_argument("--quick-per-source", type=int, default=100)
    args = p.parse_args()
    project = Path(__file__).resolve().parents[1]
    assets = project / "app/src/main/assets"
    model = assets / "language_model/model.cng"
    raw = args.ssd / "raw/aalto-ite-v88"
    quick_path = args.ssd / "experiments/keyboard-eval-v13-final/development.jsonl"
    mobile, intake = mobile_cases(raw, args.mobile_count)
    quick = quick_cases(quick_path, args.quick_per_source)
    args.output.mkdir(parents=True, exist_ok=True)
    # Bind all inputs, including code, before inference. Raw text stays local.
    paths = [model, args.probe, Path(__file__), raw/"ac_words_en.csv", raw/"sentences.csv", quick_path,
        assets/"quick/quick3.bin", assets/"quick/quick5.bin", project/"config/language_model/selected_model.json",
        project.parent/"QwenASR/crates/cantonese-ngram/src/cnn.rs",
        project.parent/"QwenASR/crates/cantonese-ngram/src/cnn_spelling.rs"]
    binding = {str(path): digest(path) for path in paths}
    print(f"Frozen {len(mobile)} mobile/preservation and {len(quick)} Quick cases", flush=True)
    results = probe(args.probe, model, [dict(left=r["left"], typed=r["typed"], spelling_limit=4096, timing=True) for r in mobile])
    spelling = spelling_metrics(mobile, results)
    print("Mobile inference and decision parity complete", flush=True)
    extra = [assets/"handwriting/lm_csr.bin" if (assets/"handwriting/lm_csr.bin").exists() else "-",
        assets/"handwriting/labels.txt", assets/"quick/quick3.bin", assets/"quick/quick5.bin"]
    requests = [dict(left=r["left"], right=r["right"], code=r["code"], version=r["version"], timing=True) for r in quick]
    previews = probe(args.probe, model, [dict(r, preview=True) for r in requests], extra)
    print("Quick previews complete", flush=True)
    full = probe(args.probe, model, requests, extra)
    ranking, latency = candidate_metrics(quick, full, previews, assets)
    if any(digest(path) != value for path, value in binding.items()):
        raise ValueError("Inputs changed during evaluation")
    report = dict(scope="Frozen development diagnostic; no fresh held-out or phone-latency claim; policies were not fit",
        source="https://zenodo.org/records/12528163", seed=SEED, input_sha256=binding,
        mobile_intake=intake, spelling=spelling, quick=ranking, quick_engine_latency=latency,
        mobile_full_pool_latency=dict(median_us=statistics.median(r["elapsedMicros"] for r in results)),
        limitations=["ITE table contains only events selected by other keyboards' autocorrection",
            "TEST_SECTION_ID is a trial, not a participant; participant map unavailable",
            "Aalto/source data and Quick development may have historical tuning exposure",
            "Prompt preservation labels are source-copy controls, not certified error-free text",
            "Spelling ablations share current retrieval; missing candidates remain failures",
            "No training or policy promotion from this audit; protected confirmation/test files were not opened"])
    (args.output/"report.json").write_text(json.dumps(report, indent=2)+"\n")
    for name, rows in (("mobile-cases", mobile), ("quick-cases", quick), ("mobile-native", results)):
        (args.output/f"{name}.jsonl").write_text("".join(json.dumps(r, ensure_ascii=False)+"\n" for r in rows))
    print(json.dumps({"spelling": {k: v["all"] for k, v in spelling.items()},
        "quick": {k: v["all"] for k, v in ranking.items()}, "latency": latency}, indent=2))


if __name__ == "__main__":
    main()
