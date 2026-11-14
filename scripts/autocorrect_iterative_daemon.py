#!/usr/bin/env python3
"""
Mac mini LAN Collector, Notifier & On-Demand Iterative Autocorrect Trainer.

Listens on 0.0.0.0:8765 for edit-pattern episodes uploaded over home Wi-Fi by
Internal Testing Track builds of Cantonese Voice Keyboard:
  - POST /api/v1/autocorrect-episodes  (stores episodes & sends notification; does NOT auto-train by default)
  - GET  /api/v1/status                (returns stored episode counts & breakdown)
  - POST /api/v1/train                 (triggers an iterative training round on demand)
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import smtplib
import subprocess
import threading
from collections import Counter
from datetime import datetime, timezone
from email.message import EmailMessage
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

import numpy as np
from filter_user_episodes import DEFAULT_CLEAN_FILE, DEFAULT_REJECTED_FILE, filter_episodes

SCRIPT_DIR = Path(__file__).resolve().parent
APP_ROOT = SCRIPT_DIR.parent
REPO_ROOT = APP_ROOT.parent

DEFAULT_DATA_DIR = APP_ROOT / "data" / "user_autocorrect"
DEFAULT_EPISODES_FILE = DEFAULT_DATA_DIR / "episodes.jsonl"
DEFAULT_NOTIFY_LOG = DEFAULT_DATA_DIR / "notifications.log"
DEFAULT_SMTP_CONFIG = Path.home() / ".config" / "autocorrect_notify.json"
DEFAULT_OUTPUT_DIR = APP_ROOT / "build" / "user-iterative-training"
PROD_ACCEPTANCE_JSON = (
    REPO_ROOT / "QwenASR" / "crates" / "cantonese-ngram" / "src" / "english_acceptance.json"
)
PROD_CHANNEL_JSON = (
    REPO_ROOT
    / "QwenASR"
    / "crates"
    / "cantonese-ngram"
    / "src"
    / "english_channel_data"
    / "channel.json"
)
VALID_WORDS_TXT = (
    REPO_ROOT / "QwenASR" / "crates" / "cantonese-ngram" / "src" / "english_valid_words.txt"
)
ASSETS_DIR = APP_ROOT / "app" / "src" / "main" / "assets"
SUGGESTION_PROBE_BIN = Path("/private/tmp/cng-host-test/release/suggestion_probe")

ACTIONABLE_SIGNALS = {
    "undo_keep_literal",
    "undo_then_edit",
    "backspace_retype_repair",
    "backspace_then_select",
    "candidate_selected",
    "candidate_keep_literal",
    "cursor_inspect_repair",
}
CONFIRMATION_SIGNALS = {
    "accepted_correction",
    "unchallenged_keep",
}


def episode_key(ep: dict[str, Any]) -> str:
    raw = json.dumps(
        [
            ep.get("ts"),
            ep.get("layout"),
            ep.get("left"),
            ep.get("typed"),
            ep.get("auto_committed"),
            ep.get("target"),
            ep.get("signal"),
        ],
        ensure_ascii=False,
        sort_keys=True,
    )
    return hashlib.sha1(raw.encode("utf-8")).hexdigest()


def load_episodes(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    episodes: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                episodes.append(json.loads(line))
            except json.JSONDecodeError:
                continue
    return episodes


def send_sync_notification(
    notify_email: str,
    new_episodes: list[dict[str, Any]],
    total_episodes: int,
    actionable_total: int,
    episodes_file: Path,
) -> dict[str, Any]:
    """
    Notify the user when new autocorrect episodes are synced from a phone:
      1. Gmail SMTP SSL if credentials exist in env (GMAIL_APP_PASSWORD) or ~/.config/autocorrect_notify.json
      2. Local /usr/sbin/sendmail fallback
      3. macOS desktop notification banner + local notifications.log
    """
    actionable_new = [ep for ep in new_episodes if ep.get("signal") in ACTIONABLE_SIGNALS]
    subject = (
        f"[Cantonese Keyboard] +{len(new_episodes)} autocorrect episodes synced "
        f"({len(actionable_new)} edits, {total_episodes} total)"
    )

    lines = [
        f"Synced at: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}",
        f"New episodes in this batch: {len(new_episodes)} ({len(actionable_new)} actionable edits)",
        f"Total stored episodes: {total_episodes} ({actionable_total} actionable edits)",
        f"Stored at: {episodes_file}",
        "",
    ]
    if actionable_new:
        lines.append("New edit patterns in this sync:")
        for ep in actionable_new[:25]:
            sig = ep.get("signal", "")
            left = ep.get("left", "")
            typed = ep.get("typed", "")
            committed = ep.get("auto_committed", "")
            target = ep.get("target", "")
            lines.append(
                f"  - [{sig}] left={left!r} typed={typed!r} auto={committed!r} -> target={target!r}"
            )
        lines.append("")

    lines.extend(
        [
            "Training was NOT started automatically (data-collection mode).",
            "When you are ready to run training on the Mac mini:",
            f"  python3 {SCRIPT_DIR / 'autocorrect_iterative_daemon.py'} --train-once",
            "or via HTTP:",
            "  curl -X POST http://127.0.0.1:8765/api/v1/train",
        ]
    )
    body = "\n".join(lines) + "\n"

    DEFAULT_NOTIFY_LOG.parent.mkdir(parents=True, exist_ok=True)
    with DEFAULT_NOTIFY_LOG.open("a", encoding="utf-8") as logf:
        logf.write(f"=== {subject} ===\n{body}\n")

    # 1. macOS Notification Center banner (non-blocking)
    try:
        banner_msg = f"+{len(new_episodes)} episodes ({len(actionable_new)} edits). Total: {total_episodes}"
        subprocess.run(
            [
                "osascript",
                "-e",
                f'display notification "{banner_msg}" with title "Keyboard Autocorrect Sync"',
            ],
            timeout=2,
            check=False,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    except Exception:
        pass

    if not notify_email:
        return {"method": "log_only", "sent": False}

    # 2. Check SMTP credentials from env or ~/.config/autocorrect_notify.json
    smtp_user = os.environ.get("GMAIL_USER", notify_email)
    smtp_pass = os.environ.get("GMAIL_APP_PASSWORD", "")
    smtp_host = os.environ.get("SMTP_HOST", "smtp.gmail.com")
    smtp_port = int(os.environ.get("SMTP_PORT", "465"))

    if not smtp_pass and DEFAULT_SMTP_CONFIG.exists():
        try:
            cfg = json.loads(DEFAULT_SMTP_CONFIG.read_text(encoding="utf-8"))
            smtp_user = cfg.get("smtp_user", smtp_user)
            smtp_pass = cfg.get("smtp_password", "")
            smtp_host = cfg.get("smtp_host", smtp_host)
            smtp_port = int(cfg.get("smtp_port", smtp_port))
        except Exception:
            pass

    msg = EmailMessage()
    msg["Subject"] = subject
    msg["From"] = smtp_user
    msg["To"] = notify_email
    msg.set_content(body)

    if smtp_pass:
        try:
            with smtplib.SMTP_SSL(smtp_host, smtp_port, timeout=10) as server:
                server.login(smtp_user, smtp_pass)
                server.send_message(msg)
            return {"method": "smtp_ssl", "recipient": notify_email, "sent": True}
        except Exception as e:
            return {"method": "smtp_ssl", "recipient": notify_email, "sent": False, "error": str(e)}

    # 3. Fallback to local sendmail / postfix queue
    if Path("/usr/sbin/sendmail").exists():
        try:
            proc = subprocess.run(
                ["/usr/sbin/sendmail", "-t", "-oi"],
                input=msg.as_bytes(),
                timeout=5,
                check=False,
                capture_output=True,
            )
            return {
                "method": "sendmail",
                "recipient": notify_email,
                "sent": proc.returncode == 0,
            }
        except Exception as e:
            return {"method": "sendmail", "recipient": notify_email, "sent": False, "error": str(e)}

    return {"method": "none", "recipient": notify_email, "sent": False}


def align_edits(typed: str, target: str) -> list[tuple[str, str, str]]:
    """Extract Damerau-Levenshtein edit operations transforming target -> typed."""
    s = target.lower().replace("’", "'")
    t = typed.lower().replace("’", "'")
    n, m = len(s), len(t)
    dp = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(n + 1):
        dp[i][0] = i
    for j in range(m + 1):
        dp[0][j] = j
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            cost = 0 if s[i - 1] == t[j - 1] else 1
            dp[i][j] = min(
                dp[i - 1][j] + 1,
                dp[i][j - 1] + 1,
                dp[i - 1][j - 1] + cost,
            )
            if i > 1 and j > 1 and s[i - 1] == t[j - 2] and s[i - 2] == t[j - 1]:
                dp[i][j] = min(dp[i][j], dp[i - 2][j - 2] + 1)

    ops: list[tuple[str, str, str]] = []
    i, j = n, m
    while i > 0 or j > 0:
        if i > 0 and j > 0 and s[i - 1] == t[j - 1] and dp[i][j] == dp[i - 1][j - 1]:
            i -= 1
            j -= 1
        elif (
            i > 1
            and j > 1
            and s[i - 1] == t[j - 2]
            and s[i - 2] == t[j - 1]
            and dp[i][j] == dp[i - 2][j - 2] + 1
        ):
            ops.append(("transposition", s[i - 2], s[i - 1]))
            i -= 2
            j -= 2
        elif i > 0 and j > 0 and dp[i][j] == dp[i - 1][j - 1] + 1:
            ops.append(("substitution", s[i - 1], t[j - 1]))
            i -= 1
            j -= 1
        elif j > 0 and dp[i][j] == dp[i][j - 1] + 1:
            prev_ch = s[i - 1] if i > 0 else "^"
            ops.append(("insertion", prev_ch, t[j - 1]))
            j -= 1
        else:
            ops.append(("deletion", s[i - 1], ""))
            i -= 1
    ops.reverse()
    return ops


def update_channel_from_episodes(
    base_channel: dict[str, Any],
    episodes: list[dict[str, Any]],
    step_discount: float = 0.08,
) -> tuple[dict[str, Any], int]:
    """Nudge character error channel costs toward observed user typo patterns."""
    updated = json.loads(json.dumps(base_channel))

    def idx(ch: str) -> int:
        if ch == "^":
            return 0
        if ch == "'":
            return 27
        if "a" <= ch <= "z":
            return ord(ch) - ord("a") + 1
        return 28

    edit_count = 0
    for ep in episodes:
        sig = ep.get("signal", "")
        if sig not in {
            "undo_then_edit",
            "backspace_retype_repair",
            "backspace_then_select",
            "candidate_selected",
            "cursor_inspect_repair",
        }:
            continue
        typed = str(ep.get("typed", "")).strip()
        target = str(ep.get("target", "")).strip()
        if not typed or not target or typed.lower() == target.lower():
            continue
        if abs(len(typed) - len(target)) > 4:
            continue
        for kind, a, b in align_edits(typed, target):
            ia, ib = idx(a), idx(b)
            if kind == "substitution" and "substitution" in updated:
                cur = updated["substitution"][ia][ib]
                updated["substitution"][ia][ib] = round(max(0.25, cur - step_discount), 4)
                edit_count += 1
            elif kind == "insertion" and "insertion" in updated:
                cur = updated["insertion"][ia][ib]
                updated["insertion"][ia][ib] = round(max(0.25, cur - step_discount), 4)
                edit_count += 1
            elif kind == "transposition" and "transposition" in updated:
                cur = updated["transposition"][ia][ib]
                updated["transposition"][ia][ib] = round(max(0.25, cur - step_discount), 4)
                edit_count += 1
            elif kind == "deletion" and "deletion" in updated:
                col = 1 if ia > 0 else 0
                cur = updated["deletion"][ia][col]
                updated["deletion"][ia][col] = round(max(0.25, cur - step_discount), 4)
                edit_count += 1
    return updated, edit_count


_SCOWL_CACHE: set[str] | None = None
_QWERTY_ROWS = ["qwertyuiop", "asdfghjkl", "zxcvbnm"]


def _get_scowl_words() -> set[str]:
    global _SCOWL_CACHE
    if _SCOWL_CACHE is None:
        if VALID_WORDS_TXT.exists():
            _SCOWL_CACHE = {
                l.strip().lower()
                for l in VALID_WORDS_TXT.read_text(encoding="utf-8").splitlines()
                if l.strip()
            }
        else:
            _SCOWL_CACHE = set()
    return _SCOWL_CACHE


def _qwerty_pos(c: str) -> tuple[int, int] | None:
    for r, row in enumerate(_QWERTY_ROWS):
        if c in row:
            return (r, row.index(c))
    return None


def _qwerty_adjacent(a: str, b: str) -> bool:
    a, b = a.lower(), b.lower()
    if a == b:
        return False
    if a == " ":
        return b in "cvbnm"
    if b == " ":
        return a in "cvbnm"
    pa, pb = _qwerty_pos(a), _qwerty_pos(b)
    return bool(pa and pb and abs(pa[0] - pb[0]) <= 1 and abs(pa[1] - pb[1]) <= 1)


def _edit_penalty(typed: str, word: str, edits: int) -> float:
    if edits == 0:
        return 0.0
    NEIGHBOR = 0.574436466482213
    OTHER = 3.7162379435897606
    SWAP = 0.9146548439690982
    EXTRA = 4.541078382355206
    MISSING = 1.9126156502899054
    t_l, w_l = typed.lower(), word.lower()
    if len(t_l) == len(w_l):
        adj = sum(1 for a, b in zip(t_l, w_l) if a != b and _qwerty_adjacent(a, b))
        non_adj = sum(1 for a, b in zip(t_l, w_l) if a != b and not _qwerty_adjacent(a, b))
        if adj + non_adj == edits:
            cost = adj * NEIGHBOR + non_adj * OTHER
        else:
            foreign = sum(1 for b in w_l if b not in t_l)
            cost = edits * SWAP + foreign * OTHER
    else:
        base = EXTRA if len(t_l) > len(w_l) else MISSING
        return base + max(0, edits - 1) * 4.5
    if edits >= 2:
        cost += (edits - 1) * 3.0
    return cost


def extract_exact_22_features(
    typed: str,
    known: bool,
    cand: dict[str, Any],
    runner: dict[str, Any] | None,
    lit_score: float | None,
    margin_cap: float = 20.0,
) -> np.ndarray:
    """Exact replica of `cnn_spelling.rs::accept()` 22-feature vector."""
    scowl_words = _get_scowl_words()
    t_c = typed.lower().replace("’", "'")
    word = str(cand["word"])
    w_c = word.lower()
    completed = int(cand.get("completedChars", cand.get("completed_chars", 0)))
    score = float(cand["score"])
    edits = int(cand["edits"])
    is_split = " " in word
    pen = 1.5 if is_split else (0.0 if completed > 0 else _edit_penalty(t_c, w_c, edits))
    raw_score = score + pen

    if runner is not None:
        r_word = str(runner["word"])
        r_comp = int(runner.get("completedChars", runner.get("completed_chars", 0)))
        r_score = float(runner["score"])
        r_edits = int(runner["edits"])
        r_split = " " in r_word
        r_pen = (
            1.5
            if r_split
            else (0.0 if r_comp > 0 else _edit_penalty(t_c, r_word.lower(), r_edits))
        )
        r_raw = r_score + r_pen
        raw_margin = min(raw_score - r_raw, margin_cap)
        pen_margin = min(score - r_score, margin_cap)
    else:
        raw_margin = margin_cap
        pen_margin = margin_cap

    ls = float(lit_score) if lit_score is not None else 0.0
    score_over_lit = (raw_score - ls) if lit_score is not None else 0.0
    len_typed = float(len(t_c))
    len_word = float(len(word))
    len_diff = float(abs(len(t_c) - len(word)))
    edit_ratio = float(edits) / max(1.0, len_typed)

    adj = non_adj = swap = extra = missing = 0.0
    if len(t_c) == len(word):
        diffs = [(a, b) for a, b in zip(t_c, w_c) if a != b]
        if len(diffs) == edits:
            adj = float(sum(1 for a, b in diffs if _qwerty_adjacent(a, b)))
            non_adj = float(sum(1 for a, b in diffs if not _qwerty_adjacent(a, b)))
        else:
            swap = float(edits)
            non_adj = float(sum(1 for b in w_c if b not in t_c))
    elif len(t_c) > len(word):
        extra = 1.0
    else:
        missing = 1.0

    first_diff = 1.0 if (t_c and w_c and t_c[0] != w_c[0]) else 0.0
    is_space_split = 1.0 if is_split else 0.0
    apos = 1.0 if ("'" in typed or "’" in typed) else 0.0
    comp_feat = float(completed)
    typed_scowl = 1.0 if t_c in scowl_words else 0.0
    cand_scowl = (
        1.0
        if (all(p in scowl_words for p in w_c.split(" ")) if is_split else (w_c in scowl_words))
        else 0.0
    )

    return np.array(
        [
            raw_score,
            ls,
            score_over_lit,
            raw_margin,
            pen_margin,
            1.0 if known else 0.0,
            len_typed,
            len_word,
            len_diff,
            float(edits),
            edit_ratio,
            adj,
            non_adj,
            swap,
            extra,
            missing,
            first_diff,
            is_space_split,
            apos,
            comp_feat,
            typed_scowl,
            cand_scowl,
        ],
        dtype=np.float64,
    )


def forward_ffn(
    X: np.ndarray,
    mean: np.ndarray,
    scales: np.ndarray,
    W1: np.ndarray,
    b1: np.ndarray,
    W2: np.ndarray,
    b2: float,
    disabled_indices: list[int],
    threshold_logit: float = 0.0,
) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    Z_in = (X - mean) / scales
    for idx in disabled_indices:
        Z_in[:, idx] = 0.0
    H_pre = Z_in @ W1.T + b1
    H = np.maximum(0.0, H_pre)
    logits = H @ W2 + b2
    probs = 1.0 / (1.0 + np.exp(-np.clip(logits - threshold_logit, -30.0, 30.0)))
    return Z_in, H, logits, probs


def fine_tune_acceptance_model(
    base_model: dict[str, Any],
    episodes: list[dict[str, Any]],
    steps: int = 250,
    lr: float = 2e-3,
    l2_anchor: float = 0.15,
) -> tuple[dict[str, Any], dict[str, Any]]:
    """
    Warm-start fine-tune the 22-24-1 ReLU acceptance network (`shared-english-acceptance-v4-dictionary-ffn`)
    using exact CNN features from `suggestion_probe`, L2-anchored to production weights.
    """
    disabled_indices: list[int] = list(base_model.get("disabled_input_indices", [1]))
    mean = np.array(base_model["means"], dtype=np.float64)
    scales = np.array(base_model["scales"], dtype=np.float64)
    W1_0 = np.array(base_model["w1"], dtype=np.float64)
    b1_0 = np.array(base_model["b1"], dtype=np.float64)
    W2_0 = np.array(base_model["w2"], dtype=np.float64)
    b2_0 = float(base_model["b2"])
    threshold = float(base_model["threshold"])
    t_logit = float(np.log(threshold / (1.0 - threshold)))
    margin_cap = float(base_model.get("margin_cap", 20.0))

    # Query `suggestion_probe` in batch for exact CNN candidate & runner scores
    reqs = [
        {"left": str(ep.get("left", "")), "code": str(ep.get("typed", "")), "version": 0, "legacy": True}
        for ep in episodes
    ]
    probe_lines: list[str] = []
    if SUGGESTION_PROBE_BIN.exists() and ASSETS_DIR.exists() and reqs:
        inp = "\n".join(json.dumps(r, ensure_ascii=False) for r in reqs) + "\n"
        out = subprocess.check_output(
            [str(SUGGESTION_PROBE_BIN), str(ASSETS_DIR)],
            input=inp.encode("utf-8"),
        ).decode("utf-8")
        probe_lines = [l for l in out.splitlines() if l.strip()]

    xs: list[np.ndarray] = []
    ys: list[float] = []
    ws: list[float] = []
    cats: list[str] = []

    for idx_ep, ep in enumerate(episodes):
        if idx_ep >= len(probe_lines):
            break
        res = json.loads(probe_lines[idx_ep]).get("english", {})
        sig = str(ep.get("signal", ""))
        typed = str(ep.get("typed", ""))
        target = str(ep.get("target", ""))
        if len(typed) < 2:
            continue
        cands = res.get("candidates", [])
        non_lit = [c for c in cands if str(c["word"]).lower() != typed.lower()]
        if not non_lit:
            continue
        c0 = non_lit[0]
        c1 = non_lit[1] if len(non_lit) > 1 else None
        comp0 = int(c0.get("completedChars", c0.get("completed_chars", 0)))
        if comp0 > 0 and sig == "candidate_selected":
            continue

        x = extract_exact_22_features(
            typed=typed,
            known=bool(res.get("known", False)),
            cand=c0,
            runner=c1,
            lit_score=res.get("literalScore"),
            margin_cap=margin_cap,
        )

        if sig == "unchallenged_keep":
            xs.append(x)
            ys.append(0.0)
            ws.append(3.0)
            cats.append("unchallenged_keep")
        elif sig in {"undo_keep_literal", "candidate_keep_literal"}:
            xs.append(x)
            ys.append(0.0)
            ws.append(8.0)
            cats.append("keep_literal")
        elif sig == "accepted_correction":
            if str(c0["word"]).lower() == target.lower():
                xs.append(x)
                ys.append(1.0)
                ws.append(1.5)
                cats.append("accepted_correction")
        else:
            if str(c0["word"]).lower() == target.lower() and comp0 == 0:
                xs.append(x)
                ys.append(1.0)
                ws.append(4.0)
                cats.append("repair_top1_target")
            elif str(c0["word"]).lower() != target.lower():
                xs.append(x)
                ys.append(0.0)
                ws.append(4.0)
                cats.append("repair_top1_wrong")

    if not xs:
        return json.loads(json.dumps(base_model)), {
            "trained_samples": 0,
            "before_loss": 0.0,
            "after_loss": 0.0,
            "before_accuracy": 1.0,
            "after_accuracy": 1.0,
        }

    X = np.vstack(xs)
    y = np.array(ys, dtype=np.float64)
    w = np.array(ws, dtype=np.float64)

    W1 = W1_0.copy()
    b1 = b1_0.copy()
    W2 = W2_0.copy()
    b2 = b2_0

    for idx in disabled_indices:
        W1[:, idx] = 0.0

    _, _, logits_before, probs_before = forward_ffn(
        X, mean, scales, W1, b1, W2, b2, disabled_indices, t_logit
    )
    eps = 1e-7
    before_loss = float(
        np.mean(
            w
            * (
                -y * np.log(np.clip(probs_before, eps, 1.0))
                - (1.0 - y) * np.log(np.clip(1.0 - probs_before, eps, 1.0))
            )
        )
    )
    preds_before = logits_before >= t_logit
    before_acc = float(np.mean(preds_before == (y >= 0.5)))

    n = float(len(y))
    for _ in range(steps):
        Z_in, H, _, probs = forward_ffn(
            X, mean, scales, W1, b1, W2, b2, disabled_indices, t_logit
        )
        d_logits = (probs - y) * w / n
        grad_W2 = H.T @ d_logits + l2_anchor * (W2 - W2_0)
        grad_b2 = float(np.sum(d_logits)) + l2_anchor * (b2 - b2_0)

        d_H = np.outer(d_logits, W2)
        d_H_pre = d_H * (H > 0.0)
        grad_W1 = d_H_pre.T @ Z_in + l2_anchor * (W1 - W1_0)
        grad_b1 = np.sum(d_H_pre, axis=0) + l2_anchor * (b1 - b1_0)

        for idx in disabled_indices:
            grad_W1[:, idx] = 0.0

        W1 -= lr * grad_W1
        b1 -= lr * grad_b1
        W2 -= lr * grad_W2
        b2 -= lr * grad_b2

        for idx in disabled_indices:
            W1[:, idx] = 0.0

    _, _, logits_after, probs_after = forward_ffn(
        X, mean, scales, W1, b1, W2, b2, disabled_indices, t_logit
    )
    after_loss = float(
        np.mean(
            w
            * (
                -y * np.log(np.clip(probs_after, eps, 1.0))
                - (1.0 - y) * np.log(np.clip(1.0 - probs_after, eps, 1.0))
            )
        )
    )
    preds_after = logits_after >= t_logit
    after_acc = float(np.mean(preds_after == (y >= 0.5)))

    cat_totals = Counter(cats)
    cat_before = Counter()
    cat_after = Counter()
    for pb, pa, yi, cat in zip(preds_before, preds_after, y, cats):
        if pb == (yi >= 0.5):
            cat_before[cat] += 1
        if pa == (yi >= 0.5):
            cat_after[cat] += 1

    updated_model = json.loads(json.dumps(base_model))
    updated_model["w1"] = [[round(float(v), 12) for v in row] for row in W1]
    updated_model["b1"] = [round(float(v), 12) for v in b1]
    updated_model["w2"] = [round(float(v), 12) for v in W2]
    updated_model["b2"] = round(float(b2), 12)

    metrics = {
        "trained_samples": len(y),
        "positive_samples": int(np.sum(y >= 0.5)),
        "negative_samples": int(np.sum(y < 0.5)),
        "before_loss": round(before_loss, 5),
        "after_loss": round(after_loss, 5),
        "before_accuracy": round(before_acc, 4),
        "after_accuracy": round(after_acc, 4),
        "category_breakdown_before": {k: f"{cat_before[k]}/{cat_totals[k]}" for k in sorted(cat_totals)},
        "category_breakdown_after": {k: f"{cat_after[k]}/{cat_totals[k]}" for k in sorted(cat_totals)},
    }
    return updated_model, metrics


class IterativeAutocorrectDaemon:
    def __init__(
        self,
        episodes_file: Path,
        output_dir: Path,
        notify_email: str = "ming030890@gmail.com",
        auto_train: bool = False,
        min_new_actionable: int = 5,
        apply_to_tree: bool = False,
    ) -> None:
        self.episodes_file = episodes_file
        self.output_dir = output_dir
        self.notify_email = notify_email
        self.auto_train = auto_train
        self.min_new_actionable = min_new_actionable
        self.apply_to_tree = apply_to_tree
        self.lock = threading.Lock()
        self.training_lock = threading.Lock()
        self.episodes_file.parent.mkdir(parents=True, exist_ok=True)
        self.output_dir.mkdir(parents=True, exist_ok=True)

        existing = load_episodes(self.episodes_file)
        self.seen_keys: set[str] = {episode_key(ep) for ep in existing}
        self.new_actionable_since_train = 0
        self.last_notification: dict[str, Any] | None = None
        self.last_training_report: dict[str, Any] | None = None
        report_path = self.output_dir / "latest" / "training_report.json"
        if report_path.exists():
            try:
                self.last_training_report = json.loads(report_path.read_text(encoding="utf-8"))
            except Exception:
                pass

    def ingest_episodes(self, incoming: list[dict[str, Any]]) -> dict[str, Any]:
        added_episodes: list[dict[str, Any]] = []
        added_actionable = 0
        with self.lock:
            with self.episodes_file.open("a", encoding="utf-8") as f:
                for ep in incoming:
                    if not isinstance(ep, dict):
                        continue
                    key = episode_key(ep)
                    if key in self.seen_keys:
                        continue
                    self.seen_keys.add(key)
                    ep["received_at"] = datetime.now(timezone.utc).isoformat()
                    f.write(json.dumps(ep, ensure_ascii=False) + "\n")
                    added_episodes.append(ep)
                    if ep.get("signal") in ACTIONABLE_SIGNALS:
                        added_actionable += 1
            self.new_actionable_since_train += added_actionable
            total_episodes = len(self.seen_keys)
            should_train = (
                self.auto_train and self.new_actionable_since_train >= self.min_new_actionable
            )

        if added_episodes:
            all_eps = load_episodes(self.episodes_file)
            cleaned_eps, rejected_eps, _ = filter_episodes(all_eps)
            DEFAULT_CLEAN_FILE.write_text(
                "\n".join(json.dumps(e, ensure_ascii=False) for e in cleaned_eps) + "\n",
                encoding="utf-8",
            )
            DEFAULT_REJECTED_FILE.write_text(
                "\n".join(json.dumps(e, ensure_ascii=False) for e in rejected_eps) + "\n",
                encoding="utf-8",
            )
            actionable_total = sum(
                1 for e in cleaned_eps if e.get("signal") in ACTIONABLE_SIGNALS
            )
            threading.Thread(
                target=self._notify_async,
                args=(added_episodes, len(cleaned_eps), actionable_total),
                daemon=True,
            ).start()

        if should_train:
            threading.Thread(target=self.run_training_round, daemon=True).start()

        return {
            "received": len(incoming),
            "added": len(added_episodes),
            "added_actionable": added_actionable,
            "total_episodes": total_episodes,
            "auto_train": self.auto_train,
        }

    def _notify_async(
        self,
        added_episodes: list[dict[str, Any]],
        total_episodes: int,
        actionable_total: int,
    ) -> None:
        result = send_sync_notification(
            notify_email=self.notify_email,
            new_episodes=added_episodes,
            total_episodes=total_episodes,
            actionable_total=actionable_total,
            episodes_file=self.episodes_file,
        )
        self.last_notification = {
            "timestamp_utc": datetime.now(timezone.utc).isoformat(),
            **result,
        }

    def status_summary(self) -> dict[str, Any]:
        raw_episodes = load_episodes(self.episodes_file)
        cleaned_episodes, rejected_episodes, filter_stats = filter_episodes(raw_episodes)
        counts = Counter(str(ep.get("signal", "unknown")) for ep in cleaned_episodes)
        actionable_total = sum(counts[s] for s in ACTIONABLE_SIGNALS)
        return {
            "status": "ok",
            "mode": "auto_train" if self.auto_train else "store_and_notify_only",
            "notify_email": self.notify_email,
            "episodes_file": str(self.episodes_file),
            "clean_episodes_file": str(DEFAULT_CLEAN_FILE),
            "raw_episodes": len(raw_episodes),
            "cleaned_episodes": len(cleaned_episodes),
            "rejected_episodes": len(rejected_episodes),
            "actionable_clean_episodes": actionable_total,
            "signals_clean": dict(counts),
            "filter_stats": filter_stats,
            "last_notification": self.last_notification,
            "last_training": self.last_training_report,
        }

    def run_training_round(self) -> dict[str, Any]:
        if not self.training_lock.acquire(blocking=False):
            return {"status": "busy", "message": "Training round already in progress"}
        try:
            with self.lock:
                raw_episodes = load_episodes(self.episodes_file)
                self.new_actionable_since_train = 0

            episodes, rejected_episodes, filter_stats = filter_episodes(raw_episodes)
            DEFAULT_CLEAN_FILE.write_text(
                "\n".join(json.dumps(e, ensure_ascii=False) for e in episodes) + "\n",
                encoding="utf-8",
            )
            DEFAULT_REJECTED_FILE.write_text(
                "\n".join(json.dumps(e, ensure_ascii=False) for e in rejected_episodes) + "\n",
                encoding="utf-8",
            )

            base_acceptance = json.loads(PROD_ACCEPTANCE_JSON.read_text(encoding="utf-8"))
            base_channel = json.loads(PROD_CHANNEL_JSON.read_text(encoding="utf-8"))

            updated_channel, channel_edits = update_channel_from_episodes(base_channel, episodes)
            updated_acceptance, ffn_metrics = fine_tune_acceptance_model(base_acceptance, episodes)

            round_ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
            latest_dir = self.output_dir / "latest"
            latest_dir.mkdir(parents=True, exist_ok=True)

            out_acceptance = latest_dir / "english_acceptance.json"
            out_channel = latest_dir / "channel.json"
            out_report = latest_dir / "training_report.json"

            out_acceptance.write_text(
                json.dumps(updated_acceptance, indent=2) + "\n", encoding="utf-8"
            )
            out_channel.write_text(json.dumps(updated_channel, indent=2) + "\n", encoding="utf-8")

            signals_counter = Counter(str(ep.get("signal", "unknown")) for ep in episodes)
            report = {
                "timestamp_utc": round_ts,
                "total_episodes": len(episodes),
                "signals": dict(signals_counter),
                "channel_edits_applied": channel_edits,
                "acceptance_ffn": ffn_metrics,
                "artifacts": {
                    "english_acceptance_json": str(out_acceptance),
                    "channel_json": str(out_channel),
                },
                "applied_to_repo_tree": False,
            }

            if self.apply_to_tree and ffn_metrics["trained_samples"] > 0:
                if ffn_metrics["after_loss"] <= ffn_metrics["before_loss"]:
                    PROD_ACCEPTANCE_JSON.write_text(
                        json.dumps(updated_acceptance, indent=2) + "\n", encoding="utf-8"
                    )
                    PROD_CHANNEL_JSON.write_text(
                        json.dumps(updated_channel, indent=2) + "\n", encoding="utf-8"
                    )
                    report["applied_to_repo_tree"] = True

            out_report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
            self.last_training_report = report
            return report
        finally:
            self.training_lock.release()


def make_handler(daemon: IterativeAutocorrectDaemon):
    class Handler(BaseHTTPRequestHandler):
        def _send_json(self, code: int, payload: dict[str, Any]) -> None:
            body = (json.dumps(payload, ensure_ascii=False) + "\n").encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self) -> None:
            if self.path.startswith("/api/v1/status") or self.path == "/health":
                self._send_json(200, daemon.status_summary())
            else:
                self._send_json(404, {"error": "not_found"})

        def do_POST(self) -> None:
            length = int(self.headers.get("Content-Length", "0"))
            raw = self.rfile.read(length).decode("utf-8", errors="replace") if length > 0 else ""

            if self.path.startswith("/api/v1/autocorrect-episodes"):
                episodes: list[dict[str, Any]] = []
                try:
                    parsed = json.loads(raw) if raw.strip() else {}
                    if isinstance(parsed, dict) and isinstance(parsed.get("episodes"), list):
                        episodes = parsed["episodes"]
                    elif isinstance(parsed, list):
                        episodes = parsed
                except json.JSONDecodeError:
                    for line in raw.splitlines():
                        line = line.strip()
                        if line:
                            try:
                                episodes.append(json.loads(line))
                            except json.JSONDecodeError:
                                continue
                result = daemon.ingest_episodes(episodes)
                self._send_json(200, {"status": "ok", **result})
            elif self.path.startswith("/api/v1/train"):
                report = daemon.run_training_round()
                self._send_json(200, {"status": "ok", "report": report})
            else:
                self._send_json(404, {"error": "not_found"})

        def log_message(self, fmt: str, *args: Any) -> None:
            ts = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
            print(f"[{ts}] {self.address_string()} - {fmt % args}", flush=True)

    return Handler


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Mac mini LAN Autocorrect Episode Collector, Notifier & On-Demand Trainer"
    )
    parser.add_argument("--host", default="0.0.0.0", help="Bind address (default: 0.0.0.0)")
    parser.add_argument("--port", type=int, default=8765, help="Bind port (default: 8765)")
    parser.add_argument(
        "--episodes-file",
        type=Path,
        default=DEFAULT_EPISODES_FILE,
        help="Path to JSONL file storing collected user episodes",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=DEFAULT_OUTPUT_DIR,
        help="Directory for iterative training artifacts and reports",
    )
    parser.add_argument(
        "--notify-email",
        default="ming030890@gmail.com",
        help="Email address to notify when new episodes are synced (default: ming030890@gmail.com)",
    )
    parser.add_argument(
        "--auto-train",
        action="store_true",
        help="Automatically trigger training after --min-new-actionable episodes (default: false; store & notify only)",
    )
    parser.add_argument(
        "--min-new-actionable",
        type=int,
        default=5,
        help="Threshold when --auto-train is enabled (default: 5)",
    )
    parser.add_argument(
        "--apply-to-tree",
        action="store_true",
        help="Automatically copy improved english_acceptance.json and channel.json to QwenASR tree on training",
    )
    parser.add_argument(
        "--train-once",
        action="store_true",
        help="Run a single training pass on existing episodes and exit",
    )
    args = parser.parse_args()

    daemon = IterativeAutocorrectDaemon(
        episodes_file=args.episodes_file,
        output_dir=args.output_dir,
        notify_email=args.notify_email,
        auto_train=args.auto_train,
        min_new_actionable=args.min_new_actionable,
        apply_to_tree=args.apply_to_tree,
    )

    if args.train_once:
        report = daemon.run_training_round()
        print(json.dumps(report, indent=2))
        return

    server = ThreadingHTTPServer((args.host, args.port), make_handler(daemon))
    print(
        f"Autocorrect Collector listening on http://{args.host}:{args.port} "
        f"(mode={'auto_train' if args.auto_train else 'store_and_notify_only'}, "
        f"notify={args.notify_email})",
        flush=True,
    )
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
