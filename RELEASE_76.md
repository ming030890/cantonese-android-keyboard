# Release 76: model casing and manual cursor correction

English, Quick3 and Quick5 now restore model-selected casing such as
`i just got an iphone from apple.` → `I just got an iPhone from Apple.`.
Casing uses the existing vocabulary and contextual scores, with a generic ranking
confidence guard and no added brand list. Sentence capitalization, byte-letter
surfaces, Chinese candidate selection, correction undo and literal recommit are
covered by the actual unified JNI scenarios.

Placing the cursor inside a committed word shows alternatives even with
Autocorrect off, including when reopening the keyboard. Choosing an alternative
replaces the complete word while preserving surrounding text; typing dismisses
stale alternatives.

Companion native source: `QwenASR` commit
`d2c340ae9838deb645d55b802191381d3cb1b748`.

Validation: 290 tests pass with the real JNI library; screenshot validation and
debug assembly pass. Paired frozen replays cover 421 ITE attempts and nine mixed
Cantonese/English groups in each layout. Exact ITE successes improve 149 → 150,
with no lost complete lexical recoveries or new clean damage. Mixed-script
results retain 7/9 exact successes and 5/5 preservation. Frozen v4 spelling score
remains 0.8946978214. See `MODEL_CASING_FIX_20261031.md` for paired metrics,
limitations, noun controls and host latency.

Build and publish with `./publish_internal.sh`, using the established Google
Play internal testing track. The release bundle includes all five Play Asset
Delivery packs. Generated binaries, model payloads and signing inputs are
excluded from source commits. Physical-device editor integration was not tested
in this session.
