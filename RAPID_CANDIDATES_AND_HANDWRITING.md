# Rapid candidate updates and handwriting alternatives

Two runtime defects are repaired without changing model weights or fitting new
acceptance parameters.

## Current candidates while typing

Previously every key cancelled the current full request and retained the last
completed row. Chinese phrase search and bilingual ranking could take longer
than the interval between keys, so the row stayed frozen until a pause.

Each key now publishes its current literal immediately. An independent native
preview worker supplies English spelling/completions and Chinese code matches.
It retains every short-code character and uses one greedy path with alternatives
at the final segment for longer codes. Full phrase search and bilingual ranking
continue on their original independent state and replace the preview when ready.
The additional state shares immutable model weights and dictionary storage.

All publications use the current request and composing snapshot. New input and
session changes cancel both native stages. Old candidates cannot commit, and a
late preview cannot overwrite completed full ranking. Literal-only publication
does not mark Chinese coverage complete. Space retains its separate correction
path and bounded deadline from the preceding repair.

On the 208-request host panel, preview median/p95 is **1.832/5.566 ms**, compared
with **2.720/295.665 ms** for the former completed request. Final current requests
remain **2.764/296.601 ms**, with identical final candidate lists and English
decisions. Every short-code Chinese preview set and preview English decision
matches the final result. Long-code previews are approximations; final phrase
candidate coverage is preserved. These are native timings, not phone latency.

## Handwriting alternatives

The visual recognizer's candidate scores were combined with negative neural LM
priors. A class absent from the neural vocabulary had an infinite missing prior
which fusion skipped, so it escaped that penalty entirely. This let unrelated
symbols dominate alternatives even when the first recognition was correct.

Missing classes now receive the weakest supported neural prior when they lack
a finite count fallback. They remain selectable using their visual evidence.
Finite count fallbacks and the visual model are unchanged.

For one existing captured `不`, the previous alternatives began
`不, ā, a, ３, z`; the repaired list begins `不, 下, 一, 可, 元`.
All 181 existing captures, including unavailable targets, are replayed with their
original empty context. Top-1/4/10 changes **110/125/134 → 126/145/156**. There are
16 newly correct first choices and no lost correct first choices on this exposed
development panel. No labels or contexts were used to select a weight or cutoff.

The [bound report](config/pipeline_audit/2026-10-23/rapid-candidates-and-handwriting.json)
retains metrics and input/native hashes. Raw visual scores, before/after outputs
and before binaries remain under ignored `build/rapid-candidate-repair/`.

Controller tests cover continuous previews with a blocked full worker, stale
candidate rejection, late-preview rejection, boundary correction and Undo. A
handwriting scenario preserves the recorded native alternative order and commits
the chosen alternative once. Updated/new screenshot baselines were rendered and
visually inspected. Host and ARM64 libraries were rebuilt. Physical-phone latency
and installation remain unverified without a connected device.
