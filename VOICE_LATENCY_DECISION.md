# Voice latency acceptance decision — Pixel 9

Updated 2026-10-18. This is the current status; the staged-loading and mmap
preparation reports retain historical experiments only.

## Decision

- **Removed:** my staged encoder/decoder prefetch task, VAD release gate,
  keyboard-start warming, explicit scratch prefaulting, and all preparation modes.
  They did not establish a repeatable latency benefit over the existing mic-time
  loading path. The idle-memory comparison against eager full-model warming was
  not evidence of an improvement over the original lazy startup.
- **Preserved:** the existing lazy mappings, encoder prefetch at mic start,
  asynchronous sequential packed-decoder prefetch, `MADV_POPULATE_READ` with OS
  fallback, CPU placement policy, one inference thread, 400 ms chunks, 800 ms
  backlog threshold, and prior decoder/finalization optimizations.
- **Retained:** reuse the immutable tokenizer within an engine. Earlier traces
  showed repeated tokenizer/prompt setup around 150–177 ms per recording; replay
  state and hypotheses are still reset for each recording.
- **Accepted after device measurements:** after a hypothesis is available, Stop
  can abandon an unpublished preview at an encoder/token boundary and proceed
  to finalization. Restore its audio cursor so the final pass consumes all audio;
  preserve completed encoder windows, valid KV prefix and published text. Do not
  cancel the first hypothesis or any final pass. No final-token cap was added.
- **Removed runtime experiment switches:** no alternate loading, preview abort,
  worker placement, kernel, thread-count, chunk or backlog policies via properties.
  Debug logging and test-only cache eviction remain explicitly gated. There is
  one production path, including the accepted Stop behavior.

## Was 2.82 → 3.93 seconds a regression?

That comparison did not establish a code regression. The 2.825 s first-text run
in `page-fault-scopes/setup` restarted the process without requesting file-cache
eviction. The newer 3.927 s run in `staged-final/ptt` also requested eviction.
The earlier first decoder prefill was 1.249 s versus 1.898 s; the next inference
pass was 0.535 s versus 1.111 s and processed a larger audio segment. Storage/cache
state and the resulting batching differences are confounded with source changes.
Do not describe the entire difference as proven cache cost or proven code cost.

The real Stop-path problem is directly visible: a preview started before Stop
can continue decoding before finalization. Changes in preparation timing can
also change the number of previews performed. Faster early progress therefore
need not mean faster Stop-to-Idle.

## Matched tests before the decision

Actual Pixel 9, serial `4C070DLAQ000NM`, real IME/editor/JNI/VAD, real-time replay
of the same 3.858 s `ptt_guardrail.wav`. Eight fresh-process invocations, each
with one first query and one warm repeat (16 queries). Same installed native
binary; preparation order was none/staged/staged/none in each cache condition.
`none` selected the existing mic-time loading path; it retained tokenizer reuse.
All runs confirmed complete PCM, editor output and packaged native hash.

| Condition | Existing path: first text | Staged: first text |
| --- | ---: | ---: |
| Process restart, ordinary cache | 4.464 / 1.939 s | 3.788 / 1.779 s |
| Process restart, eviction requested | 3.925 / 4.871 s | 3.966 / 4.050 s |

Ordinary-cache order effects are large: restarting an app does not clear Android's
file cache. Even eviction is advisory. These small samples do not establish a
first-text win for staging, and none is claimed. The prior report of 3.93 s
versus 3.97–4.05 s was interim; the later baseline trial was 4.87 s and is retained
here rather than discarded.

## Stop-fix measurements

`decision-after` has four fresh-process first/warm pairs (8 queries), two with
ordinary cache and two with eviction requested, after removing staged loading
and adding preview cancellation. It preceded removal of the unused debug
optimization switches; all those switches were unset in these tests.

| Metric | Existing path, cancellation absent (4 warm queries) | Stop fix (4 warm queries) |
| --- | ---: | ---: |
| First editor text | 1.724–1.863 s | 1.727–1.746 s |
| Stop → Idle | 2.110–3.134 s | 2.136–2.307 s |
| Median Stop → Idle | 3.114 s | 2.254 s |
| Median request → Idle | 6.978 s | 6.128 s |

The observed median Stop improvement is about 0.86 s (28%). Some baseline trials
were already faster than the candidate; this is a reduction in the slow cases,
not a guarantee that every query improves. Different preview counts and source
preparation remain potential variation; do not extrapolate to all utterances.
First-text latency has no demonstrated improvement from this fix.

A concrete warm-trace comparison shows where the saving occurs (logcat timestamps,
approximately millisecond precision):

| Native milestone | Existing path, `decision-before/natural-4-none` | Final build, `decision-final/ptt-natural` |
| --- | ---: | ---: |
| JNI Stop call → final inference starts | 1,127 ms | 62 ms |
| Final inference itself | 1,997 ms | 1,976 ms |
| App Stop → Idle | 3,129 ms | 2,046 ms |

Final inference is still approximately two seconds. The accepted fix removes
replaceable work ahead of it; it does not make the final decoder compute faster.

Candidate transcripts retained the baseline PTT words; punctuation varied.
The final binary is separately validated below. Raw traces log
`event=preview_cancelled` where the accepted behavior actually runs.

## Final-build validation

Final packaged native SHA-256:
`82cd01ce642b09e5826a939cce3eeef6080facba353e14954664390e824f3675`.
Results: `build/voice-benchmark/decision-final/`. Sixteen complete recordings plus
one no-mic idle probe. Every case verified packaged native identity, PCM acceptance
and editor delivery. The PTT words and both Cantonese clip transcripts matched
prior results; PTT punctuation varied.

| Clip / condition | First query: first text / Stop→Idle | Warm query: first text / Stop→Idle |
| --- | ---: | ---: |
| PTT, ordinary cache | 3.674 / 2.238 s | 1.718 / 2.046 s |
| PTT, eviction requested | 4.243 / 2.551 s | 1.754 / 2.204 s |
| Cantonese `cv_yue_001`, eviction requested | 4.258 / 3.079 s | 1.954 / 2.568 s |
| Two utterances, eviction requested | 4.214 / 1.918 s | 2.337 / 1.919 s |
| PTT, detailed logging disabled | 1.990 / 2.397 s | 1.783 / 2.126 s |

The Cantonese and multiple-utterance cases validate recognition and lifecycle;
they are not matched before/after latency trials. Logging-disabled runs emitted
zero detailed `VoiceLatency`/`VoiceNative` lines.

Rapid Stop/start at 0/50/200 ms preserved both query transcripts, accepted every
PCM chunk and verified no native-session overlap in all three pairs. Idle model
RSS was 15,140 KiB (14.8 MiB), versus a 1.92 GiB virtual mapping; whole-debug-app
RSS was 314,316 KiB (307.0 MiB). Opening the keyboard did not prefetch the full model.
The original keyboard selection and disabled tracing/cache-eviction properties
were restored after testing.

Verification:

- Pixel 9 native unit tests: 22 passed.
- Host native tests: 37 passed (19 library, 7 audio, 11 kernels).
- Android unit tests: 235 total, 219 passed, 16 skipped, zero failures.
- Debug bundle and release Kotlin compilation succeeded.
- Required `:app:verifyKeyboard`: the same 22 existing screenshot mismatches out
  of 63. No screenshot baseline updates; all nine tracked unrelated screenshot
  files retained their recorded hashes/deletion state.
- Python runner compiles; native and Android Git whitespace checks pass.

## Run the retained app path

Build native JNI through the existing deployment build and install the debug
bundle with its five model packs. For an independently signed diagnostic copy,
use `-PvoiceBenchmark=true` and bundletool local testing. The installed original
app and its data are preserved.

```sh
# Fresh process, OS file cache retained, followed by a warm query.
python3 scripts/benchmark_voice.py ../QwenASR/bench/android-assets/ptt_guardrail.wav \
  --serial 4C070DLAQ000NM \
  --package com.madeinhk.cantonesevoicekeyboard.voicebenchmark \
  --output build/voice-benchmark/repeat-natural

# Separate storage-cold control. Eviction is advisory: inspect read/residency data.
python3 scripts/benchmark_voice.py ../QwenASR/bench/android-assets/ptt_guardrail.wav \
  --serial 4C070DLAQ000NM \
  --package com.madeinhk.cantonesevoicekeyboard.voicebenchmark \
  --cold-cache --output build/voice-benchmark/repeat-evicted

# Rapid Stop/start ownership and full-audio checks.
python3 scripts/benchmark_voice.py ../QwenASR/bench/android-assets/ptt_guardrail.wav \
  --serial 4C070DLAQ000NM \
  --package com.madeinhk.cantonesevoicekeyboard.voicebenchmark \
  --restart-delays 0,50,200 --output build/voice-benchmark/repeat-restart
```

Add `--no-trace` for a natural-cache check without detailed logs. Debug diagnostics
require both `BuildConfig.DEBUG` and the explicit test property. Release ignores
the diagnostics property; ordinary recordings avoid stage counter syscalls.
There is no `--prepare-mode` or alternative optimization path to select.

## Next work

Measure publication cadence and prefill/decoder compute on longer continuous
speech. Optimize only against matched cache conditions and clips, with transcript
checks, first-text time, update gaps and Stop-to-Idle reported separately. Avoid
another preload policy unless device measurements establish a net benefit.
