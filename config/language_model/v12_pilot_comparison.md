# Matched capacity learning curves

Four configurations received exactly1,000updates, assessed every200updates.
All use the same34,081-token vocabulary, residual width256, FFN512, data order,
seed, objective and optimizer. Added embedding dimensions and the third block
preserve the common initializer's predictions (32checked cases, max difference0).
The initializer is the expanded-vocabulary v7-derived network, not exactly the
original v7 probability distribution. Context deltas compare against original v7.

| Embedding / blocks | Export MB | Mean bigram top1 at600/800/1000 | Mean top3 | Late context guards passed |
|---|---:|---:|---:|---:|
|128 /2|5.86|79.89%|95.82%|2/3|
|128 /3|6.13|79.84%|95.80%|3/3|
|224 /2|9.45|79.50%|95.84%|3/3|
|224 /3|9.72|79.74%|96.02%|3/3|

All count curves rise quickly and flatten around400steps. Neither a larger
embedding nor an extra block consistently dominates. The128/3late top1 curve
rises79.09→79.69→80.75%, while128/2is79.17→80.37→80.11%. The selected128/3
passes the context guard at all three late milestones; its1000-step worst
context top3 change is−0.92pp, versus−1.12pp for128/2.

Choose128/3for the full continuation: a modest, low-cost capacity increase
(270,704bytes) with a continuing late rise and context retention passing.
224dimensions cost another3.59MB without a consistent accuracy lead. This is a
practical single-seed choice, not proof of superiority. No endpoint-loss winner,
held-out-test selection, or statistical-significance claim is made.
256embedding dimensions would produce10.65MBwith two blocks, exceeding the hard
10,000,000-byte limit;224fits both depths without changing vocabulary.

Count accuracy measures TRAIN/lexical teacher memorization, not typing accuracy.
Context validation is repeatedly used development data,1000rows/source for five
sources. Final test remains separate from architecture selection.

Curves: `v12_learning_curves.png`.
Canonical checkpoint/curve/selection files are on external SSD under
`/Volumes/SSD/cantonese_ngram/experiments/dense-capacity-v12-1000`.
Full run: `/Volumes/SSD/cantonese_ngram/experiments/dense-capacity-v12-full`.
