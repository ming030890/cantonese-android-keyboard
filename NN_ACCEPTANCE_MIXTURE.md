# Neural Network Autocorrect Acceptance Policy & Training Mixture

**Date:** 2026-10-25  
**Model Schema:** `shared-english-acceptance-v4-dictionary-ffn`  
**Architecture:** `22-24-1-relu` Feed-Forward Neural Network (Single Unified Policy)  
**Global Decision Threshold:** $\tau = 1.05$ (`threshold: 0.740774899182154`)  
**SHA-256:** `a53bb69e97bbf9215829abf6f36997c21900a3c38c10112721372d8719f750f1`

---

## 1. Architectural Philosophy: Occam's Razor

Earlier experiments investigated "localized" secondary thresholds (hardcoded branching for short words $\le 3$ characters with 1-neighbor substitutions). That approach was rejected in accordance with Occam's Razor:

1. **Native Feature Coverage**: The acceptance network already accepts 22 continuous inputs including:
   - `typed_len` (Feature 6)
   - `candidate_len` (Feature 7)
   - `len_diff` (Feature 8)
   - `total_edits` (Feature 9)
   - `edit_ratio` (Feature 10: $\text{edits} / \text{length}$)
   - `neighbor_replacements` (Feature 11)
2. **Smooth Non-Linear Modeling**: With 24 hidden ReLU neurons, the network inherently possesses the capacity to model non-linear length and edit-ratio interactions.
3. **Single Decision Rule**: The keyboard engine maintains exactly **one clean mathematical decision rule** across all word lengths:
   $$\text{Accept if } z = \mathbf{w}_2^\top \text{ReLU}(\mathbf{W}_1 \mathbf{x} + \mathbf{b}_1) + b_2 \ge \tau$$
   No hardcoded `if/else` length branches, no fragmented threshold lookup tables, and zero heuristic tech debt.

---

## 2. Training Data Mixture & Provenance

The neural network was trained on **28,116 total rows** using Adam optimization with anchor regularization to protect long-word baseline behavior while learning data-supported improvements for short words.

### Component Breakdown
| Source | Row Count | Description |
| :--- | :---: | :--- |
| `real` | 9,728 | Recorded keystrokes and real participant typing sentences |
| `generated` | 14,289 | Synthetically perturbed word-level and context-level edit variations |
| `blank` | 1,004 | Isolated/empty-context unigram inputs |
| `gboard_context` | 785 | Diverse sentence context distillations |
| `gboard_word` | 748 | Vocabulary-level teacher agreement subset |
| `gboard_touch` | 1,283 | Prior mobile touch and typo distributions |
| **`gboard_short_teacher` (NEW)** | **342** | **Canary Gboard Android 17 neural touch-fuzzed short-word distillation** |

### The New Short-Word Teacher Addition:
- Extracted from 1,205 real user sentences across 3,552 short-word candidates.
- Evaluated on Google Pixel Android 17 Canary emulator with Gboard's live neural language models (`LM 7.en`, `LM 20.en`, `kc_tflite_model`) and realistic key-boundary touch fuzzing ($65\%$ seam interpolation + Gaussian jitter).
- Ingested 342 high-confidence teacher decisions (267 neighbor-slip corrections and 75 clean-word controls).

### Training Hyperparameters:
- **Optimizer:** Adam ($\beta_1 = 0.9, \beta_2 = 0.999, \epsilon = 10^{-8}$)
- **Learning Rate:** $0.001$
- **Epochs:** 60
- **Batch Size:** 512
- **Logit Anchoring:** $\text{anchor\_grad} = 0.02 \times (z - z_{\text{parent}})$ on non-distilled rows to prevent drift on established vocabulary.

---

## 3. Empirical Verification & Benchmark Results

### A. 400 Short-Word Benchmark (Length $\le 3$)
Evaluated on 400 real-context short-word inputs (250 neighbor slips, 75 swaps, 75 clean controls):

| Model | Typo Recall | Clean Preservation | Precision |
| :--- | :---: | :---: | :---: |
| **Gboard Canary (At Space)** | 75.7% (246 / 325) | 100.0% (75 / 75) | 100.0% |
| **Gboard Canary (+1 Word Retro)** | 80.9% (263 / 325) | 100.0% (75 / 75) | 97.5% |
| **Our Previous Baseline FFN** | 80.3% (261 / 325) | 100.0% (75 / 75) | 100.0% |
| **Our Retrained Unified FFN** | **83.1% (270 / 325)** | **100.0% (75 / 75)** | **100.0%** |

*Result:* The retrained NN adds **+9 correct repairs** on short words with **zero clean-word damage**, outperforming Gboard's space bar by **+7.4%** and exceeding Gboard even after retro-lookahead (+2.2%).

### B. 5,261 Frozen Benchmark Suite (Whole-Language Evaluation)
Evaluated on the full static objective suite across all word lengths to verify zero collateral regressions on long words:

| Metric | Baseline Accepted FFN | Retrained Unified FFN | Delta |
| :--- | :---: | :---: | :---: |
| **Objective Score ($F_{0.5}$)** | `0.858041` | **`0.861321`** | **`+0.003280`** (+32.8 bps) |
| **Total Correct Fixes (TP)** | 898 | **916** | **+18 correct repairs** |
| **Clean Word Damage** | 7 | **7** | **0 added clean damage** |
| **False Corrections (FP)** | 62 | 63 | +1 |

---

## 4. Integration Details

- Policy definition: [`QwenASR/crates/cantonese-ngram/src/english_acceptance.json`](file:///Users/tony/Documents/keyboard/QwenASR/crates/cantonese-ngram/src/english_acceptance.json)
- Selected model manifest: [`cantonese-voice-keyboard/config/language_model/selected_model.json`](file:///Users/tony/Documents/keyboard/cantonese-voice-keyboard/config/language_model/selected_model.json)
- Rust JNI implementation: [`QwenASR/crates/cantonese-ngram/src/cnn_spelling.rs`](file:///Users/tony/Documents/keyboard/QwenASR/crates/cantonese-ngram/src/cnn_spelling.rs)
- Training script: [`gboard-probe/improve-v3/train.py`](file:///Users/tony/Documents/keyboard/gboard-probe/improve-v3/train.py)
