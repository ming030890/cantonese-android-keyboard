# Pixel 9 validation of the five latency proposals

Historical experiment: the preview-abort implementation, fastest-core preference
and two-thread production change were subsequently removed at the user's request.
Only evidence is retained here; those optional implementation paths are no longer
available. See [retained changes](VOICE_LATENCY_KEPT_CHANGES.md).

Date: 2026-10-18. Measurements use the actual debug IME on Pixel 9
(`4C070DLAQ000NM`), real-time audio replay, packaged optimized JNI and real editor
insertion. Each invocation force-stops the process, then runs a cold query and a
warm repeat. The original Play app is preserved.

## Verdict on the claims

| Proposal | Finding |
| --- | --- |
| Abort an in-flight preview on Stop | Valid experiment, with conditional benefits. Tested cooperatively at safe boundaries; the predicted fixed 300–400 ms saving is not established. |
| Finalize only a tail with a sample-count token cap | The explanation is incorrect for this implementation. Stable text already conditions decoding, cached prefill is reused where valid, and the active token ceiling is 16. A cap inferred from audio duration can truncate words and does not remove encoder/prefill work. Not adopted. |
| Move join/cleanup after Idle | Final text already reaches the editor before worker teardown. UI readiness and engine ownership can be separated, but declaring Idle earlier does not make recognition finish earlier. No unsafe early engine reuse was introduced. |
| Reduce 400 ms chunks to 250–300 ms | Tested. Results vary by clip and metric; smaller chunks do not consistently improve first text or completion. The claimed 41 ms compute/359 ms idle premise is false. |
| Bypass onset Local Agreement | The gate controls stable commitment. The app already displays provisional hypotheses before that commitment. Bypassing it is not a direct first-visible-word optimization, and no confidence-based bypass exists in the current code. Not changed. |

## Corrections to the quoted breakdown

The saved `stream-400ms-test` warm trace has `session_ready=41.46 ms`, which is
session startup, not inference time. Its native inference passes took 543.68,
438.30, 793.22, 810.27 and 761.59 ms before the 1728.12 ms final pass. These are
wall-clock processing calls, not 41 ms of work per 400 ms audio chunk.

That same run showed first visible text at 1760.58 ms after recording began, or
1396.58 ms after the native VAD's padded speech-start position. This VAD-derived
onset is not a manually annotated acoustic onset. Text did not update once per
400 ms chunk: hypotheses are emitted after inference returns, and unchanged
hypotheses are not published again.

The quoted 2.38-second Stop-to-Idle result comes from another valid saved trace,
`status-ptt-new`, built with a different native hash. It is not the same experiment
as the 400 ms first-text result. Historical traces are useful context, but cannot
be combined into a single configuration's latency budget.

## Experiment implementation

The current source had already changed since the 2026-10-17 baseline: it uses
400 ms inference chunks, an 800 ms queue-deferral threshold, asynchronous decoder
prefetch and per-recording coordinator sessions serialized around the engine.
The old baseline tables remain historical measurements of earlier source.

For this validation, every trial uses the same new native binary. CPU policy,
two threads, packed kernels, bounded prefetch, 800 ms backlog threshold and
deferred previews after Stop are held fixed. Only `chunk_ms` and
`abort_preview` vary. Condition order is 400/off, 300/off, 250/off, 250/on,
300/on, 400/on, then a repeated 400/off control, alternating clip order. This is
an exploratory one-phone comparison, not a statistically powered performance study.

The new abort experiment is **off by default** and only enabled with latency
tracing plus `debug.qwen_asr.abort_preview=1`. It checks Stop before a preview,
after encoding and between autoregressive steps. It cannot preempt a matrix
kernel or a prefill already executing. When interrupted, it restores the audio
cursor, invalidates speculative prefill reuse and preserves committed token
history plus valid encoder caches. Finalization ignores the abort flag and must
still consume all accepted audio. Aborting work may make finalization repeat
some work or withhold an intermediate update; it is not guaranteed to help.

Per-pass logs now record generated-token count, the actual token ceiling, stable
prefix length, new audio duration, prefill reuse, decode timings and decoder-step
ready times. These are raw model-step timings, including control/end tokens;
they are not character intervals or display-frame timestamps. The runner also
records chunk/backlog/abort overrides and the aggregate thermal status.

## Results

All 32 queries completed with full PCM, zero rejected chunks, complete native
traces and the same verified packaged library. The short-clip transcripts
retain the same words; comma placement varies on the mixed-language clip.
The two-utterance recording preserves both utterances. This is limited
regression coverage, not a large-corpus recognition-accuracy evaluation.

Warm short-clip results (seconds; both 400/off controls are shown as ranges):

| Clip | Chunk | Abort | First editor text | Stop → Idle | Max gap between updates while recording | Actual aborted previews |
| --- | ---: | --- | ---: | ---: | ---: | ---: |
| ptt | 400 ms | off | 1.78–1.83 | 2.27–2.71 | 0.76–0.88 | 0,0 |
| ptt | 300 ms | off | 2.16 | 2.63 | 0.82 | 0 |
| ptt | 250 ms | off | 2.00 | 2.45 | 0.71 | 0 |
| ptt | 250 ms | on | 2.04 | 2.25 | 0.71 | 1 |
| ptt | 300 ms | on | 2.12 | 1.86 | 0.93 | 0 |
| ptt | 400 ms | on | 1.84 | 2.29 | 0.82 | 1 |
| cv001 | 400 ms | off | 2.03–2.09 | 2.30–2.32 | 0.96–0.97 | 0,0 |
| cv001 | 300 ms | off | 1.84 | 2.51 | 1.34 | 0 |
| cv001 | 250 ms | off | 2.20 | 2.21 | 1.62 | 0 |
| cv001 | 250 ms | on | 2.18 | 2.08 | 1.43 | 1 |
| cv001 | 300 ms | on | 1.79 | 1.90 | 1.17 | 1 |
| cv001 | 400 ms | on | 2.03 | 2.14 | 0.96 | 1 |

Update-gap estimates use only the few changed hypotheses before Stop (two to
four updates in these warm queries), excluding the gap containing finalization.
They do not support a robust p95 estimate. Raw timelines, including post-Stop
gaps and all cold queries, are retained in the analysis JSON.

Interpretation:

- 300 ms improves Cantonese first text by about 0.20–0.26 s versus 400 ms,
  but its abort-off completion is about 0.19–0.20 s slower. It makes
  mixed-language first text about 0.33–0.38 s slower. 250 ms does not
  improve warm first text on either reference clip in these trials.
- Abort-on reduces some completion times, but the effect is not universal.
  The 400 ms mixed-language warm result is within the spread of the two
  abort-off controls. The 300 ms mixed-language warm trial did not actually
  abort a preview, so its faster completion cannot be credited to cancellation.
- The 300 ms Cantonese warm trial did abort a preview and completed about
  0.61 s sooner than its single abort-off comparison. More repeated trials
  with Stop at controlled phase boundaries are needed to establish the effect.
- Full-query maximum update gaps can increase with abort: a preview that
  would have arrived after Stop is withheld in favor of the final result.
  Evaluate responsiveness during recording and Stop-to-final separately.

Additional two-utterance recording, 8.472 s of audio, 400 ms chunks:

| Query | Stop → Idle, abort off | Stop → Idle, abort on | Actual aborts with on |
| --- | ---: | ---: | ---: |
| cold | 2.37 s | 1.89 s | 1 |
| warm | 2.59 s | 1.83 s | 1 |

The observed two-utterance completion improvements are approximately 0.48 s
cold and 0.76 s warm, with identical final text. They are useful evidence
for this candidate, not a fixed saving for every utterance.

Logs show 13 actual interrupted previews: eight at decode boundaries and five
after encoding. Both multi-utterance interruptions and the 300 ms Cantonese
interruptions occurred after encoding, before prefill. Those larger improvements
therefore do not establish that a check only inside the decode loop would save
as much. The prototype deliberately tests both safe boundaries.

All recorded per-query Android thermal statuses were 0; battery temperature
across invocation metadata ranged from 26.4°C to 35.6°C. CPU scheduling,
filesystem caching and run-to-run variance remain uncontrolled. Tracing is
enabled equally for every condition; its overhead was not separately measured.

The new raw decoder-step intervals across the two-clip matrix have a median
of approximately 70 ms (observed range 66–137 ms). This is the cost between
model-step outputs during decode, not a claim of one visible word every 70 ms:
encoder/prefill and batch-level publication still dominate visible gaps.

Packaged native SHA-256:
`6c5eb7a68bb599a50696ebaeced5d45f4bd1faa40ad43a7ba8a287ba21895f66`.

Evidence: [all short-clip trial metrics](build/voice-benchmark/ideas-validation/analysis-summary.json),
[matrix driver summary](build/voice-benchmark/ideas-validation/matrix-summary.json).
Each case directory contains result JSON, override/device metadata and live logcat:

- [00-cv001-chunk400-abort0](build/voice-benchmark/ideas-validation/00-cv001-chunk400-abort0/result.json), [log](build/voice-benchmark/ideas-validation/00-cv001-chunk400-abort0/logcat.txt)
- [00-ptt-chunk400-abort0](build/voice-benchmark/ideas-validation/00-ptt-chunk400-abort0/result.json), [log](build/voice-benchmark/ideas-validation/00-ptt-chunk400-abort0/logcat.txt)
- [01-cv001-chunk300-abort0](build/voice-benchmark/ideas-validation/01-cv001-chunk300-abort0/result.json), [log](build/voice-benchmark/ideas-validation/01-cv001-chunk300-abort0/logcat.txt)
- [01-ptt-chunk300-abort0](build/voice-benchmark/ideas-validation/01-ptt-chunk300-abort0/result.json), [log](build/voice-benchmark/ideas-validation/01-ptt-chunk300-abort0/logcat.txt)
- [02-cv001-chunk250-abort0](build/voice-benchmark/ideas-validation/02-cv001-chunk250-abort0/result.json), [log](build/voice-benchmark/ideas-validation/02-cv001-chunk250-abort0/logcat.txt)
- [02-ptt-chunk250-abort0](build/voice-benchmark/ideas-validation/02-ptt-chunk250-abort0/result.json), [log](build/voice-benchmark/ideas-validation/02-ptt-chunk250-abort0/logcat.txt)
- [03-cv001-chunk250-abort1](build/voice-benchmark/ideas-validation/03-cv001-chunk250-abort1/result.json), [log](build/voice-benchmark/ideas-validation/03-cv001-chunk250-abort1/logcat.txt)
- [03-ptt-chunk250-abort1](build/voice-benchmark/ideas-validation/03-ptt-chunk250-abort1/result.json), [log](build/voice-benchmark/ideas-validation/03-ptt-chunk250-abort1/logcat.txt)
- [04-cv001-chunk300-abort1](build/voice-benchmark/ideas-validation/04-cv001-chunk300-abort1/result.json), [log](build/voice-benchmark/ideas-validation/04-cv001-chunk300-abort1/logcat.txt)
- [04-ptt-chunk300-abort1](build/voice-benchmark/ideas-validation/04-ptt-chunk300-abort1/result.json), [log](build/voice-benchmark/ideas-validation/04-ptt-chunk300-abort1/logcat.txt)
- [05-cv001-chunk400-abort1](build/voice-benchmark/ideas-validation/05-cv001-chunk400-abort1/result.json), [log](build/voice-benchmark/ideas-validation/05-cv001-chunk400-abort1/logcat.txt)
- [05-ptt-chunk400-abort1](build/voice-benchmark/ideas-validation/05-ptt-chunk400-abort1/result.json), [log](build/voice-benchmark/ideas-validation/05-ptt-chunk400-abort1/logcat.txt)
- [06-cv001-chunk400-abort0](build/voice-benchmark/ideas-validation/06-cv001-chunk400-abort0/result.json), [log](build/voice-benchmark/ideas-validation/06-cv001-chunk400-abort0/logcat.txt)
- [06-ptt-chunk400-abort0](build/voice-benchmark/ideas-validation/06-ptt-chunk400-abort0/result.json), [log](build/voice-benchmark/ideas-validation/06-ptt-chunk400-abort0/logcat.txt)
- [multi-chunk400-abort0](build/voice-benchmark/ideas-validation/multi-chunk400-abort0/result.json), [log](build/voice-benchmark/ideas-validation/multi-chunk400-abort0/logcat.txt)
- [multi-chunk400-abort1](build/voice-benchmark/ideas-validation/multi-chunk400-abort1/result.json), [log](build/voice-benchmark/ideas-validation/multi-chunk400-abort1/logcat.txt)

## What the tail-only proposal misses

In the fresh 400 ms mixed-language control, the warm final pass already conditions
on four stable tokens and generates eight additional tokens, with a ceiling of
16. Its unprocessed audio span is about 1594 ms, not necessarily the last 400 ms:
inference can fall behind capture. With the proposed `tail_samples / 1280`
formula, that span permits about 19 tokens and therefore would not reduce the
existing 16-token limit. Applying the formula to an assumed 400 ms tail would
permit only five tokens, fewer than the eight actually generated.

Across the initial two-clip matrix, final passes generate 0–10 tokens, with
stable prefixes of 1–11 tokens and the same ceiling of 16. Some final passes
generate no new text tokens yet still pay encoder/prefill costs. There is no
fixed relationship between audio duration and the number of tokenizer pieces
needed to complete an utterance, especially with rollback and uncommitted text.

The useful optimization question is whether valid encoder/prefill work can be
reused, or whether completion is provably unnecessary when no new relevant audio
remains. That requires cache and endpoint correctness checks, not an arbitrary
token cap or assuming the tentative hypothesis is 90% correct.

## Visible text, completion, and teardown

In the fresh 400 ms mixed-language warm control, provisional text reaches the
editor at 1.779 s while the first stable-delta callback is at 2.540 s. The onset
consensus gate is therefore not preventing that initial visible text.

The last changed hypothesis is not the same as inference completion. A final
pass may confirm a hypothesis without changing it, so `Idle - last changed text`
can include substantial inference and must not be labeled thread teardown.
Use final-inference end, worker join and JNI return milestones to isolate cleanup.
Across the 28 fresh single-utterance queries, completion callback → JNI return
was 60–106 ms (median 66 ms), followed by 0.7–6.9 ms to Idle. The former interval
is not a pure thread-join measurement; it also includes native exit/cleanup and
prompt reset. These observations do not support a guaranteed 80–140 ms saving.
Likewise, an early Idle signal must not allow a new inference session to reuse
the engine before its worker has joined. The current coordinator already buffers
new capture while serializing engine ownership.

## Reproduce the retained baseline

The historical abort/CPU experiments above are no longer available in the cleaned
source. Do not pass `abort_preview` or `prime_core` expecting those paths to run.
The recorded JSON/logs retain the original evidence.

After rebuilding and installing the cleaned diagnostic app, run the real-IME
baseline using source defaults:

```sh
export VOICE_DEVICE=4C070DLAQ000NM
for key in worker_pin threads matmul_m1 bounded_prefetch defer_stop chunk_ms backlog_ms; do
  adb -s "$VOICE_DEVICE" shell setprop "debug.qwen_asr.$key" '""'
done
python3 scripts/benchmark_voice.py \
  ../QwenASR/bench/android-assets/ptt_guardrail.wav \
  --serial "$VOICE_DEVICE" --runs 2 \
  --package com.madeinhk.cantonesevoicekeyboard.voicebenchmark \
  --output build/voice-benchmark/my-clean-baseline
```

The app runner restores the prior IME/trace flag and closes the diagnostic activity.
Require complete PCM, zero rejected chunks, complete native traces and matching
packaged/local hashes; inspect transcripts separately. Native changes require a
fresh JNI build and app installation before testing. See [retained changes](VOICE_LATENCY_KEPT_CHANGES.md)
and [the build notes](VOICE_LATENCY_STATUS.md).

## Checks and follow-up

- On-device native suite: 20 passed, including cancellation/finalization behavior,
  cursor/history preservation, packed-kernel parity and pool resizing.
- Host native library/audio/kernel tests: 36 passed.
- Current Android unit/scenario suite: 217 passed, 16 skipped, zero failures.
- Diagnostic AAB built and installed with model packs available. Required keyboard
  gate remains red on the same 22 existing screenshot mismatches out of 63.
- No original Play app replacement, model change, final-token cap, consensus
  bypass or early resource release was made. The experimental abort code has since been removed.

Next, repeat promising cancellation cases with Stop triggered at controlled
encoder/prefill/decode boundaries rather than relying only on the recording's
natural end. Measure changed hypotheses during recording separately from the
gap containing finalization. For smoother output, investigate publishing safe,
revisable partial hypotheses during decode; changing stable commitment alone
does not supply that behavior. Keep transcript/endpoint/restart correctness as
acceptance checks before promoting any scheduling policy.
