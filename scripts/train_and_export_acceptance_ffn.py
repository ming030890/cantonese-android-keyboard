#!/usr/bin/env python3
"""Train champion FFN acceptance policy on combined real + synthetic typo data.

Inspired by Typewise error injection, spatial QWERTY distance, and over-aggressive
autocorrect guards. Calibrates F0.5 cutoff on selection split, verifies held-out
evaluation, and exports weights directly to english_acceptance.json for pure Rust inference.
"""
from collections import Counter
import hashlib
import json
from pathlib import Path
import sys

import numpy as np
import torch
import torch.nn as nn
import torch.optim as optim

SCRIPTS_DIR = Path(__file__).resolve().parent
PROJECT_DIR = SCRIPTS_DIR.parent
sys.path.insert(0, str(SCRIPTS_DIR))

from iterate_english_policy import metric, split, f05
from benchmark_single_edit import POSITIONS
from audit_candidate_pipeline import penalty, digest
from analyze_single_edit_root_cause import restoration

FEATURE_NAMES = [
    'raw_lm_score', 'literal_score', 'score_over_literal', 'raw_runner_margin', 'penalized_runner_margin',
    'known_word', 'typed_len', 'candidate_len', 'len_diff', 'total_edits', 'edit_ratio',
    'neighbor_replacements', 'other_replacements', 'swap_or_alternation',
    'extra_character', 'missing_character', 'first_character_change',
    'is_space_split', 'observed_apostrophe'
]


def extract_features(case, output):
    surfaces = {e['word'] for e in output.get('apostropheEvidence', [])}
    ranked = sorted((r for r in output['candidates'] if r['word'] not in surfaces), key=lambda r: (-r['score'], r['word']))
    if not ranked:
        return None, None
    b = ranked[0]
    typed = case['typed'].lower()
    word = b['word'].lower()
    literal = output.get('literalScore')
    if len(typed) < 3 or len(word) < 3 or b['completedChars'] or (output['known'] and literal is None):
        return None, b['word']

    is_split = 1.0 if ' ' in word else 0.0
    p = penalty(typed, word, b['edits']) if not b['completedChars'] and not is_split else (1.5 if is_split else 0.)
    raw_score = b['score'] + p
    if len(ranked) > 1:
        r2 = ranked[1]
        r2_split = 1.0 if ' ' in r2['word'] else 0.0
        p2 = penalty(typed, r2['word'].lower(), r2['edits']) if not r2['completedChars'] and not r2_split else (1.5 if r2_split else 0.)
        raw_margin = min(raw_score - (r2['score'] + p2), 20.)
        pen_margin = min(b['score'] - r2['score'], 20.)
    else:
        raw_margin = 20.0
        pen_margin = 20.0
    lit_score = literal if literal is not None else 0.0

    len_typed = float(len(typed))
    len_word = float(len(word))
    len_diff = float(abs(len(typed) - len(word)))
    edits = float(b['edits'])
    edit_ratio = edits / max(len_typed, 1.0)

    adj = non_adj = swap = extra = missing = 0.0
    first_diff = 1.0 if (typed and word and typed[0] != word[0]) else 0.0
    if len(typed) == len(word):
        diffs = [(x, y) for x, y in zip(typed, word) if x != y]
        if len(diffs) == b['edits']:
            for x, y in diffs:
                if x in POSITIONS and y in POSITIONS and all(abs(k - l) <= 1 for k, l in zip(POSITIONS[x], POSITIONS[y])):
                    adj += 1.0
                else:
                    non_adj += 1.0
        else:
            swap = edits
            non_adj = float(sum(ch not in typed for ch in word))
    else:
        if len(typed) > len(word):
            extra = 1.0
        else:
            missing = 1.0

    apostrophe_feat = 1.0 if ("'" in typed or '’' in typed) else 0.0

    feats = [
        raw_score, lit_score, raw_score - lit_score, raw_margin, pen_margin,
        1.0 if output['known'] else 0.0, len_typed, len_word, len_diff, edits, edit_ratio,
        adj, non_adj, swap, extra, missing, first_diff, is_split, apostrophe_feat
    ]
    return feats, b['word']


class AcceptanceFFN(nn.Module):
    def __init__(self, in_dim=19, hidden_dim=16):
        super().__init__()
        self.fc1 = nn.Linear(in_dim, hidden_dim)
        self.relu = nn.ReLU()
        self.fc2 = nn.Linear(hidden_dim, 1)

    def forward(self, x):
        return self.fc2(self.relu(self.fc1(x))).squeeze(-1)


def calibrate_threshold(cases_sub, words_sub, restored_sub, logits_sub, valid_sub):
    typo_count = sum(c['typed'].lower() != c['target'].lower() for c in cases_sub)
    active = [k for k in range(len(cases_sub)) if not restored_sub[k] and valid_sub[k]]
    active.sort(key=lambda k: -logits_sub[k])
    tp = sum(bool(restored_sub[k]) and cases_sub[k]['typed'].lower() != cases_sub[k]['target'].lower() and restored_sub[k].lower() == cases_sub[k]['target'].lower() for k in range(len(cases_sub)))
    fp = sum(bool(restored_sub[k]) and (cases_sub[k]['typed'].lower() == cases_sub[k]['target'].lower() or restored_sub[k].lower() != cases_sub[k]['target'].lower()) for k in range(len(cases_sub)))
    best_f = f05(tp, fp, typo_count - tp)
    best_th = float('inf')
    for pos, k in enumerate(active):
        hit = (cases_sub[k]['typed'].lower() != cases_sub[k]['target'].lower() and words_sub[k].lower() == cases_sub[k]['target'].lower())
        tp += hit
        fp += (not hit)
        if pos + 1 < len(active) and logits_sub[active[pos + 1]] == logits_sub[k]:
            continue
        cur_f = f05(tp, fp, typo_count - tp)
        if cur_f > best_f:
            best_f = cur_f
            best_th = float(logits_sub[k])
    return best_f, best_th


def main():
    cases_p = PROJECT_DIR / 'build/acceptance-refit-2026-10-21/cases.jsonl'
    gen_out_p = PROJECT_DIR / 'build/acceptance-refit-2026-10-21/generated-native.json'
    real_cache_p = PROJECT_DIR / 'build/apostrophe-native-cache.json'
    blank_cache_p = PROJECT_DIR / 'build/single-edit-root-cause-2026-10-21/empty-context-native.json'
    model_p = PROJECT_DIR / 'app/src/main/assets/language_model/model.cng'
    policy_out_p = PROJECT_DIR.parent / 'QwenASR/crates/cantonese-ngram/src/english_acceptance.json'
    apostrophe_p = policy_out_p.with_name('english_apostrophe_acceptance.json')
    apostrophe = json.loads(apostrophe_p.read_text())

    print('Loading 35,776 multi-tier dataset...')
    all_cases = [json.loads(l) for l in cases_p.open()]
    real_out = json.loads(real_cache_p.read_text())['outputs']
    gen_out = json.loads(gen_out_p.read_text())['outputs']
    blank_out = json.loads(blank_cache_p.read_text())['outputs']
    all_outputs = real_out + gen_out + blank_out
    assert len(all_cases) == len(all_outputs)

    print('Extracting features...')
    X_all = []
    words_all = []
    for c, o in zip(all_cases, all_outputs):
        f, w = extract_features(c, o)
        X_all.append(f)
        words_all.append(w)

    partitions = [split(c['group']) for c in all_cases]
    restored = [restoration(apostrophe, o) for o in all_outputs]

    train_idx = [i for i, p in enumerate(partitions) if p == 'train' and X_all[i] is not None]
    counts = Counter(all_cases[i]['source'] for i in train_idx)
    print(f'Train source composition: {dict(counts)}')

    sel_real_idx = [i for i, c in enumerate(all_cases) if c['source'] == 'real' and partitions[i] == 'selection']
    eval_real_idx = [i for i, c in enumerate(all_cases) if c['source'] == 'real' and partitions[i] == 'evaluation']

    X_train = np.array([X_all[i] for i in train_idx], dtype=np.float32)
    y_train = np.array([1 if all_cases[i]['typed'].lower() != all_cases[i]['target'].lower() and words_all[i].lower() == all_cases[i]['target'].lower() else 0 for i in train_idx], dtype=np.float32)
    weights = np.array([1.0 if all_cases[i]['source'] == 'real' else 0.5 * counts['real'] / counts[all_cases[i]['source']] for i in train_idx], dtype=np.float32)

    mean = X_train.mean(axis=0)
    std = np.maximum(X_train.std(axis=0), 1e-4)
    X_train_norm = (X_train - mean) / std

    def get_norm_subset(idx_list):
        arr = []
        valid = []
        for i in idx_list:
            if X_all[i] is not None:
                arr.append(X_all[i])
                valid.append(True)
            else:
                arr.append([0.0] * len(FEATURE_NAMES))
                valid.append(False)
        return (np.array(arr, dtype=np.float32) - mean) / std, valid

    X_sel_norm, sel_valid = get_norm_subset(sel_real_idx)
    X_eval_norm, eval_valid = get_norm_subset(eval_real_idx)

    print('Training Acceptance FFN (19 -> 16 -> 1)...')
    torch.manual_seed(42)
    model = AcceptanceFFN(len(FEATURE_NAMES), 16)
    criterion = nn.BCEWithLogitsLoss(weight=torch.tensor(weights, dtype=torch.float32))
    optimizer = optim.AdamW(model.parameters(), lr=0.01, weight_decay=1e-3)
    x_t = torch.tensor(X_train_norm, dtype=torch.float32)
    y_t = torch.tensor(y_train, dtype=torch.float32)

    for epoch in range(350):
        optimizer.zero_grad()
        loss = criterion(model(x_t), y_t)
        loss.backward()
        optimizer.step()

    model.eval()
    with torch.no_grad():
        sel_logits = model(torch.tensor(X_sel_norm, dtype=torch.float32)).numpy()
        eval_logits = model(torch.tensor(X_eval_norm, dtype=torch.float32)).numpy()

    sel_cases = [all_cases[i] for i in sel_real_idx]
    eval_cases = [all_cases[i] for i in eval_real_idx]
    sel_words = [words_all[i] for i in sel_real_idx]
    eval_words = [words_all[i] for i in eval_real_idx]
    sel_restored = [restored[i] for i in sel_real_idx]
    eval_restored = [restored[i] for i in eval_real_idx]
    eval_outputs = [all_outputs[i] for i in eval_real_idx]

    sel_f, threshold = calibrate_threshold(sel_cases, sel_words, sel_restored, sel_logits, sel_valid)
    print(f'Calibrated Selection F0.5: {sel_f:.4f} at logit threshold {threshold:.4f}')

    # Sigmoid threshold
    sig_threshold = float(1.0 / (1.0 + np.exp(-threshold)))
    print(f'Sigmoid probability threshold: {sig_threshold:.4f}')

    # Evaluate on held-out evaluation partition
    eval_decisions = []
    for k in range(len(eval_cases)):
        if eval_restored[k]:
            eval_decisions.append(eval_restored[k])
        elif eval_valid[k] and eval_logits[k] >= threshold:
            eval_decisions.append(eval_words[k])
        else:
            eval_decisions.append(None)

    m_eval = metric(eval_cases, eval_outputs, [eval_outputs[k]['candidates'] for k in range(len(eval_cases))], eval_decisions, 'evaluation')
    m_base = metric(eval_cases, eval_outputs, [eval_outputs[k]['candidates'] for k in range(len(eval_cases))], [eval_outputs[k]['correction'] for k in range(len(eval_cases))], 'evaluation')

    print('\n=== HELD-OUT EVALUATION COMPARISON ===')
    print(f'Baseline production: correct={m_base["correct"]}, wrong={m_base["wrong"]}, false_changes={m_base["false_changes"]}, F0.5={m_base["f05"]:.4f}, Prec={m_base["precision"]:.4f}, Rec={m_base["recall"]:.4f}')
    print(f'Champion FFN:        correct={m_eval["correct"]}, wrong={m_eval["wrong"]}, false_changes={m_eval["false_changes"]}, F0.5={m_eval["f05"]:.4f}, Prec={m_eval["precision"]:.4f}, Rec={m_eval["recall"]:.4f}')

    # Export weights for Rust inference
    w1 = model.fc1.weight.detach().cpu().numpy().tolist()  # [16, 19]
    b1 = model.fc1.bias.detach().cpu().numpy().tolist()    # [16]
    w2 = model.fc2.weight.detach().cpu().numpy()[0].tolist() # [16]
    b2 = float(model.fc2.bias.detach().cpu().item())       # scalar

    policy_payload = {
        'schema': 'shared-english-acceptance-v3-ffn',
        'model_sha256': digest(model_p),
        'architecture': '19-16-1-relu',
        'features': FEATURE_NAMES,
        'means': mean.tolist(),
        'scales': std.tolist(),
        'w1': w1,
        'b1': b1,
        'w2': w2,
        'b2': b2,
        'threshold': sig_threshold,
        'threshold_logit': float(threshold),
        'margin_cap': 20.0,
        'minimum_observed_length': 3,
        'minimum_candidate_length': 3,
        'preserve_known_words': False,
        'provenance': {
            'method': 'FFN with learned edit costs and length interactions, trained on real + synthetic typo data',
            'evaluation': {
                'cases': m_eval['cases'],
                'typos': m_eval['typos'],
                'correct': m_eval['correct'],
                'wrong': m_eval['wrong'],
                'false_changes': m_eval['false_changes'],
                'f05': m_eval['f05'],
                'precision': m_eval['precision'],
                'recall': m_eval['recall'],
            },
            'baseline': {
                'cases': m_base['cases'],
                'typos': m_base['typos'],
                'correct': m_base['correct'],
                'wrong': m_base['wrong'],
                'false_changes': m_base['false_changes'],
                'f05': m_base['f05'],
                'precision': m_base['precision'],
                'recall': m_base['recall'],
            }
        }
    }

    policy_out_p.write_text(json.dumps(policy_payload, indent=2) + '\n')
    print(f'\nExported updated FFN policy to {policy_out_p}')


if __name__ == '__main__':
    main()
