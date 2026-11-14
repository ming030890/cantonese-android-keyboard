#!/usr/bin/env python3
"""
Deterministic Data Sanitizer & Filter for User Autocorrect Episodes.

Applies 6 filters to transform noisy raw keyboard episodes into verified,
high-precision training pairs:
  1. Partial-Word Prefix Reconstruction: merges trailing word fragments in `left`
     (e.g. left='Can y', typed='oi', target='ou' -> left='Can ', typed='yoi', target='you').
  2. Chained-Edit & Future-Context Lookahead Recovery: checks subsequent episodes'
     `left` context to verify the final word that actually remained in the text
     (recovering e.g. jcon->ocon->icon, weakwar->weakesr->weakest, thasw->thwse->these,
     ken->mem->men).
  3. Punctuation-Undo Reclassification: when `typed != auto_committed` and the user
     undid only the trailing space to add punctuation so `target == auto_committed`
     (e.g. dridau -> Friday.), reclassifies as `accepted_correction`.
  4. Mind-Change / Rephrasing Rejection: drops pairs where the user changed their
     mind or inserted a word before the original word (e.g. 'asian' -> 'east Asian',
     or clearing the field 'no' -> 'o').
  5. Target Lexicon & Plausibility Gate: requires `target` for repairs to be a known
     English word or user-attested term (asr, gplay, viet, tbf, deeplink, github)
     and within plausible typo/prefix distance of `typed`.
  6. Unchallenged-Keep Verification: filters out unfixed typos (e.g. 'midrl', 'ww',
     'stre') from `unchallenged_keep` using dictionary + future-context checks.
"""

from __future__ import annotations

import json
import re
from collections import Counter
from pathlib import Path
from typing import Any

SCRIPT_DIR = Path(__file__).resolve().parent
APP_ROOT = SCRIPT_DIR.parent
REPO_ROOT = APP_ROOT.parent

DEFAULT_EPISODES_FILE = APP_ROOT / "data" / "user_autocorrect" / "episodes.jsonl"
DEFAULT_CLEAN_FILE = APP_ROOT / "data" / "user_autocorrect" / "episodes_clean.jsonl"
DEFAULT_REJECTED_FILE = APP_ROOT / "data" / "user_autocorrect" / "episodes_rejected.jsonl"
CNG_SRC = REPO_ROOT / "QwenASR" / "crates" / "cantonese-ngram" / "src"
WORDS_TSV = CNG_SRC / "english_valid_words.txt"
LEXICON_TSV = CNG_SRC / "english_channel_data" / "lexicon.tsv"
PRIORS_TSV = CNG_SRC / "english_channel_data" / "priors.tsv"


def load_known_words() -> tuple[set[str], set[str]]:
    """
    Returns (valid_vocab, cng_spellings):
      - valid_vocab: union of all bundled lexicons (`english_valid_words.txt`,
        `lexicon.tsv`, `priors.tsv`) for general English validity.
      - cng_spellings: the 34,293 canonical spellings in `priors.tsv` (`model.cng`
        `english_word_ids`) that the on-device candidate generator can actually produce.
    """
    words: set[str] = set()
    cng_spellings: set[str] = set()
    for path in (WORDS_TSV, LEXICON_TSV, PRIORS_TSV):
        if path.exists():
            for line in path.read_text(encoding="utf-8").splitlines():
                parts = line.strip().split("\t")
                if parts and parts[0]:
                    w = parts[0].lower().replace("’", "'")
                    if len(w) > 1 or w in {"a", "i"}:
                        words.add(w)
                        if path == PRIORS_TSV:
                            cng_spellings.add(w)
    # Common contractions & single-letter words ([byte:61]='a', [byte:69]='i' in model.cng)
    contractions = {
        "a",
        "i",
        "don't",
        "won't",
        "can't",
        "didn't",
        "doesn't",
        "isn't",
        "aren't",
        "wasn't",
        "weren't",
        "haven't",
        "hasn't",
        "hadn't",
        "wouldn't",
        "couldn't",
        "shouldn't",
        "i'm",
        "i've",
        "i'll",
        "i'd",
        "you're",
        "you've",
        "you'll",
        "we're",
        "we've",
        "they're",
        "it's",
        "that's",
        "there's",
        "what's",
        "who's",
        "let's",
    }
    words.update(contractions)
    cng_spellings.update(contractions)
    return words, cng_spellings


def osa_distance(a: str, b: str) -> int:
    a = a.lower().replace("’", "'")
    b = b.lower().replace("’", "'")
    n, m = len(a), len(b)
    if n == 0:
        return m
    if m == 0:
        return n
    dp = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(n + 1):
        dp[i][0] = i
    for j in range(m + 1):
        dp[0][j] = j
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            cost = 0 if a[i - 1] == b[j - 1] else 1
            dp[i][j] = min(
                dp[i - 1][j] + 1,
                dp[i][j - 1] + 1,
                dp[i - 1][j - 1] + cost,
            )
            if i > 1 and j > 1 and a[i - 1] == b[j - 2] and a[i - 2] == b[j - 1]:
                dp[i][j] = min(dp[i][j], dp[i - 2][j - 2] + 1)
    return dp[n][m]


def is_plausible_typo_or_prefix(typed: str, target: str) -> bool:
    a = typed.lower().replace("’", "'")
    b = target.lower().replace("’", "'")
    if not a or not b or a == b:
        return False
    # Prefix completion (e.g. 'recommendatio' -> 'recommendation', 'origi' -> 'original')
    if b.startswith(a) and 1 <= len(b) - len(a) <= 7:
        return True
    # Noisy prefix completion (e.g. 'sugveatio' -> 'suggestion', 'untetestin' -> 'interesting', 'ibdependenyl' -> 'independently')
    if len(b) >= 6 and len(a) >= 5 and len(b) >= len(a):
        prefix_b = b[: len(a)]
        if osa_distance(a, prefix_b) <= 3:
            return True
    if abs(len(a) - len(b)) > 3:
        return False
    max_edits = 1 if min(len(a), len(b)) <= 2 else (2 if min(len(a), len(b)) <= 5 else 3)
    return osa_distance(a, b) <= max_edits


def split_trailing_word_prefix(left: str) -> tuple[str, str]:
    """If `left` ends with ASCII letters (mid-word backspace), split into (base_left, word_prefix)."""
    m = re.search(r"([A-Za-z'’]+)$", left)
    if not m:
        return left, ""
    prefix = m.group(1)
    base_left = left[: -len(prefix)]
    return base_left, prefix


def recover_final_word_from_future_left(
    base_left: str,
    typed: str,
    target: str,
    auto_c: str,
    future_episodes: list[dict[str, Any]],
) -> str | None:
    """
    Look ahead in subsequent episodes' `left` context to find the actual word
    committed immediately after `base_left`.
    """
    anchor = base_left[-36:] if len(base_left) > 36 else base_left
    for f_ep in future_episodes:
        f_left = str(f_ep.get("left", ""))
        if anchor:
            pos = f_left.find(anchor)
            if pos == -1:
                continue
            rem = f_left[pos + len(anchor) :]
        else:
            if not f_left or "\n" in f_left[:25]:
                continue
            rem = f_left
        m = re.match(r"^([A-Za-z'’]+)[\s.,!?;:，。！？；：]", rem)
        if m:
            candidate = m.group(1)
            # When base_left is empty, ensure `f_left` belongs to the same sentence
            # (not a brand-new query after pressing Enter/Send).
            if not anchor:
                if not any(
                    candidate.lower() == ref.lower() or is_plausible_typo_or_prefix(ref, candidate)
                    for ref in (typed, target, auto_c)
                    if ref
                ):
                    continue
            return candidate
    return None


def extract_bounded_left(left: str) -> str:
    """
    Extract the context after the last hard sentence/paragraph boundary
    (`\\n`, `\\r`, `.`, `!`, `?`, `。`, `！`, `？`), matching `tokenizer.rs::boundaries()`.
    Protects URLs (`http...`, `www...`) and decimal numbers (`12.34`).
    """
    if not left:
        return ""
    url_spans = [m.span() for m in re.finditer(r"(?i)https?://[^\s<>]+|www\.[^\s<>]+", left)]
    last_boundary = 0
    for idx, ch in enumerate(left):
        if any(s <= idx < e for s, e in url_spans):
            continue
        prev_ch = left[idx - 1] if idx > 0 else ""
        next_ch = left[idx + 1] if idx + 1 < len(left) else ""
        is_hard = ch in "。！？!?\n\r" or (
            ch == "."
            and prev_ch != "."
            and next_ch != "."
            and not (prev_ch.isdigit() and next_ch.isdigit())
        )
        if is_hard:
            last_boundary = idx + 1
    return left[last_boundary:].lstrip(" \t")


def filter_episodes(
    episodes: list[dict[str, Any]],
    known_words: set[str] | None = None,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], dict[str, Any]]:
    if known_words is None:
        known_words, cng_spellings = load_known_words()
    else:
        _, cng_spellings = load_known_words()

    # Pass 0: Automatically mine self-attested literal words (no hardcoded whitelist):
    # any word where the user explicitly triggered `undo_keep_literal` or repaired to `target`
    # AND left `target` intact in subsequent `left` context.
    attested_literals: set[str] = set()
    for i, ep in enumerate(episodes):
        sig0 = str(ep.get("signal", ""))
        if sig0 in {
            "undo_keep_literal",
            "candidate_keep_literal",
            "backspace_retype_repair",
            "undo_then_edit",
            "candidate_selected",
        }:
            t = str(ep.get("target", "")).lower().replace("’", "'")
            if len(t) < 2 and t not in {"a", "i"}:
                continue
            base_left, pfx = split_trailing_word_prefix(str(ep.get("left", "")))
            full_t = pfx.lower() + t
            final_w = recover_final_word_from_future_left(
                base_left,
                str(ep.get("typed", "")),
                full_t,
                str(ep.get("auto_committed", "")),
                episodes[i + 1 : i + 8],
            )
            if final_w and final_w.lower().replace("’", "'") == full_t:
                if sig0 in {"undo_keep_literal", "candidate_keep_literal"} or is_plausible_typo_or_prefix(
                    pfx + str(ep.get("typed", "")), full_t
                ):
                    attested_literals.add(full_t)

    valid_vocab = known_words | attested_literals

    cleaned: list[dict[str, Any]] = []
    rejected: list[dict[str, Any]] = []
    stats = Counter()
    seen_clean_keys: set[tuple[str, str, str, str]] = set()
    skip_indices: set[int] = set()

    for i, raw_ep in enumerate(episodes):
        if i in skip_indices:
            continue
        ep = dict(raw_ep)
        sig = str(ep.get("signal", ""))
        raw_left = str(ep.get("left", ""))
        raw_typed = str(ep.get("typed", ""))
        raw_auto = str(ep.get("auto_committed", ""))
        raw_target = str(ep.get("target", ""))

        # Filter 1: Reconstruct partial-word backspace in Quick mode
        base_left, word_prefix = split_trailing_word_prefix(raw_left)
        if word_prefix and sig in {
            "backspace_retype_repair",
            "backspace_then_select",
            "candidate_selected",
        }:
            # Check if the previous episode was the first half of a chained repair at `base_left`
            # (e.g. [254] weakwar -> weakesr, followed by [255] left='...weakes', typed='r' -> 't')
            if (
                cleaned
                and cleaned[-1].get("left") == base_left
                and str(cleaned[-1].get("target", "")).startswith(word_prefix)
            ):
                # Already handled or can be merged into previous episode
                prev = cleaned[-1]
                reconstructed_target = word_prefix + raw_target
                if reconstructed_target.lower() in valid_vocab:
                    prev["target"] = reconstructed_target
                    prev["filter_note"] = "merged_chained_suffix_repair"
                    stats["merged_chained_suffix_repair"] += 1
                    continue

            ep["left"] = base_left
            ep["typed"] = word_prefix + raw_typed
            ep["auto_committed"] = word_prefix + raw_auto
            ep["target"] = word_prefix + raw_target
            ep["filter_note"] = f"reconstructed_prefix:{word_prefix}"
            stats["reconstructed_partial_word"] += 1

        left = str(ep["left"])
        typed = str(ep["typed"])
        auto_c = str(ep["auto_committed"])
        target = str(ep["target"])
        future_eps = episodes[i + 1 : i + 10]
        future_word = recover_final_word_from_future_left(left, typed, target, auto_c, future_eps)

        # Filter 3: Punctuation-boundary undo where target == auto_committed and typed != target
        # (e.g. dridau -> Friday, user backspaced space to type 'Friday.')
        if sig in {"undo_then_edit", "undo_keep_literal"} and target.lower() == auto_c.lower() and typed.lower() != target.lower():
            if target.lower() in valid_vocab:
                ep["signal"] = "accepted_correction"
                ep["filter_note"] = "reclassified_punctuation_undo_to_accepted"
                sig = "accepted_correction"
                stats["reclassified_punctuation_undo"] += 1

        # Actionable signals
        if sig in {
            "undo_keep_literal",
            "candidate_keep_literal",
            "undo_then_edit",
            "backspace_retype_repair",
            "backspace_then_select",
            "candidate_selected",
            "cursor_inspect_repair",
        }:
            # Check if this is a keep-literal signal
            if sig in {"undo_keep_literal", "candidate_keep_literal"}:
                # An `undo_keep_literal` event is frequently emitted by `flushPending()` when
                # the user presses Backspace once to start clearing/deleting a word (e.g.
                # `github` -> `GitHub` then clearing the search box, or `ca` -> `Ca` then
                # clearing, or `menriom` -> `mention` then editing).
                # Therefore, a keep-literal signal is ONLY valid if `future_word` explicitly
                # confirms that `target` remained in the text (exact case match for casing
                # undos, or case-insensitive match for lexical undos).
                is_casing_only_undo = typed.lower() == auto_c.lower() and typed != auto_c
                if future_word is None:
                    ep["reject_reason"] = f"unconfirmed_undo_literal_cleared_or_end:{target}"
                    rejected.append(ep)
                    stats["rejected_aborted_undo_literal"] += 1
                    continue

                if is_casing_only_undo:
                    if future_word != target:
                        ep["reject_reason"] = f"casing_undo_not_kept_in_future:{target}!={future_word}"
                        rejected.append(ep)
                        stats["rejected_aborted_undo_literal"] += 1
                        continue
                elif future_word.lower() != target.lower():
                    if future_word.lower() in valid_vocab and is_plausible_typo_or_prefix(typed, future_word):
                        ep["signal"] = "undo_then_edit"
                        ep["target"] = future_word
                        ep["filter_note"] = f"recovered_from_future:{target}->{future_word}"
                        cleaned.append(ep)
                        stats["recovered_target_from_future"] += 1
                    else:
                        ep["reject_reason"] = f"undo_literal_not_kept_in_future:{target}!={future_word}"
                        rejected.append(ep)
                        stats["rejected_aborted_undo_literal"] += 1
                    continue

                if target.lower() not in valid_vocab:
                    ep["reject_reason"] = f"unattested_nonword_literal:{target}"
                    rejected.append(ep)
                    stats["rejected_unattested_literal"] += 1
                    continue

                cleaned.append(ep)
                stats["kept_clean_keep_literal"] += 1
                continue

            # Otherwise it's a repair/selection signal (typed -> target)
            # Filter 2: Chained-Edit / Intermediate-Typo Recovery via future `left` context
            if target.lower() not in valid_vocab or (
                future_word
                and future_word.lower() != target.lower()
                and future_word.lower() in valid_vocab
                and is_plausible_typo_or_prefix(typed, future_word)
            ):
                # Check if the user inserted `target` BEFORE `auto_c` (Mind-change / word insertion, e.g. [182] asian -> east Asian)
                if future_word and future_word.lower() == target.lower():
                    # Check if the word right after `target` in future `left` is `auto_c`!
                    anchor = (left + target + " ")[-36:]
                    inserted_before = any(
                        (anchor in str(fe.get("left", "")))
                        and str(fe.get("left", ""))
                        .split(anchor, 1)[1]
                        .lower()
                        .startswith(auto_c.lower())
                        for fe in future_eps
                    )
                    if inserted_before:
                        ep["reject_reason"] = f"word_insertion_rephrase:{typed}->{target}+{auto_c}"
                        rejected.append(ep)
                        stats["rejected_mind_change_rephrase"] += 1
                        continue

                if future_word and future_word.lower() in valid_vocab and is_plausible_typo_or_prefix(typed, future_word):
                    # Mark intermediate chained episodes at the same left context to skip
                    for offset in range(1, min(4, len(episodes) - i)):
                        next_ep = episodes[i + offset]
                        next_base, _ = split_trailing_word_prefix(str(next_ep.get("left", "")))
                        if next_base == left:
                            skip_indices.add(i + offset)
                    ep["target"] = future_word
                    ep["filter_note"] = f"chained_repair_recovered:{target}->{future_word}"
                    target = future_word
                    stats["recovered_chained_repair"] += 1

            # If the user selected a candidate (`candidate_selected`), e.g. `democrac` -> `Democrat`
            # or `ther` -> `the`, but then immediately overwrote or deleted it in `future_word`
            # (e.g. `Democrat` -> `Demo Party`, `Hi the` -> `Hi folks`), reject as aborted!
            next_left_0 = str(episodes[i + 1].get("left", "")) if i + 1 < len(episodes) else ""
            if sig == "candidate_selected" and (
                (
                    future_word
                    and future_word.lower() != target.lower()
                    and not future_word.lower().startswith(target.lower())
                )
                or (
                    not left
                    and next_left_0
                    and len(target) >= 4
                    and next_left_0.strip() != target
                    and target.lower().startswith(next_left_0.strip().lower())
                )
            ):
                ep["reject_reason"] = f"candidate_selected_overwritten_in_future:{target}->{future_word or next_left_0.strip()}"
                rejected.append(ep)
                stats["rejected_overwritten_candidate_selection"] += 1
                continue

            # Filter 4: Mind-change / rephrasing check (even when `target` is in valid_vocab, e.g. 'asian' -> 'east')
            if future_word and future_word.lower() == target.lower():
                anchor = (left + target + " ")[-36:]
                inserted_before = any(
                    (anchor in str(fe.get("left", "")))
                    and str(fe.get("left", ""))
                    .split(anchor, 1)[1]
                    .lower()
                    .startswith(auto_c.lower())
                    for fe in future_eps
                )
                if inserted_before:
                    ep["reject_reason"] = f"word_insertion_rephrase:{typed}->{target}+{auto_c}"
                    rejected.append(ep)
                    stats["rejected_mind_change_rephrase"] += 1
                    continue

            # Filter 5: Target must be in valid_vocab and a plausible typo/prefix of `typed`
            if target.lower() not in valid_vocab:
                ep["reject_reason"] = f"target_not_in_vocab:{target}"
                rejected.append(ep)
                stats["rejected_target_not_in_vocab"] += 1
                continue

            if not is_plausible_typo_or_prefix(typed, target):
                ep["reject_reason"] = f"implausible_edit_distance:{typed}->{target}"
                rejected.append(ep)
                stats["rejected_implausible_distance"] += 1
                continue

            # Deduplicate identical bursts (e.g. [101], [102] duplicate 'q' -> 'a')
            dedup_key = (left[-32:], typed.lower(), target.lower(), sig)
            if dedup_key in seen_clean_keys:
                ep["reject_reason"] = "duplicate_burst_episode"
                rejected.append(ep)
                stats["rejected_duplicate_burst"] += 1
                continue
            seen_clean_keys.add(dedup_key)

            cleaned.append(ep)
            stats["kept_clean_repair"] += 1
            continue

        # Confirmation signals: accepted_correction & unchallenged_keep
        if sig == "accepted_correction":
            if target.lower() not in valid_vocab:
                ep["reject_reason"] = f"accepted_nonword:{target}"
                rejected.append(ep)
                stats["rejected_unverified_confirmation"] += 1
                continue
            if future_word and future_word.lower() != target.lower():
                ep["reject_reason"] = f"accepted_overwritten_later:{target}->{future_word}"
                rejected.append(ep)
                stats["rejected_overwritten_confirmation"] += 1
                continue
            cleaned.append(ep)
            stats["kept_clean_accepted"] += 1
            continue

        if sig == "unchallenged_keep":
            # Filter 6: Drop unchallenged_keep if the word is an unknown typo (e.g. 'midrl', 'ww', 'stre')
            # or was later edited in future context
            if target.lower() not in valid_vocab:
                ep["reject_reason"] = f"unchallenged_unknown_typo:{target}"
                rejected.append(ep)
                stats["rejected_unchallenged_typo"] += 1
                continue
            if future_word and future_word.lower() != target.lower():
                ep["reject_reason"] = f"unchallenged_overwritten_later:{target}->{future_word}"
                rejected.append(ep)
                stats["rejected_overwritten_confirmation"] += 1
                continue
            cleaned.append(ep)
            stats["kept_clean_unchallenged"] += 1

    for ep in cleaned:
        b_left = extract_bounded_left(str(ep.get("left", "")))
        ep["bounded_left"] = b_left
        ep["bounded_sentence_start"] = (b_left.strip() == "")
        ep["target_in_cng_vocab"] = (str(ep.get("target", "")).lower().replace("’", "'") in cng_spellings)

    return cleaned, rejected, dict(stats)


def main() -> None:
    if not DEFAULT_EPISODES_FILE.exists():
        print(f"No episodes file at {DEFAULT_EPISODES_FILE}")
        return
    raw_eps = [
        json.loads(line)
        for line in DEFAULT_EPISODES_FILE.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    cleaned, rejected, stats = filter_episodes(raw_eps)

    DEFAULT_CLEAN_FILE.write_text(
        "\n".join(json.dumps(e, ensure_ascii=False) for e in cleaned) + "\n",
        encoding="utf-8",
    )
    DEFAULT_REJECTED_FILE.write_text(
        "\n".join(json.dumps(e, ensure_ascii=False) for e in rejected) + "\n",
        encoding="utf-8",
    )

    print(f"Raw episodes:      {len(raw_eps)}")
    print(f"Cleaned episodes:  {len(cleaned)}")
    print(f"Rejected episodes: {len(rejected)}")
    print("Filter breakdown:", json.dumps(stats, indent=2))


if __name__ == "__main__":
    main()
