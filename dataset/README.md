# Datasets

Dataset tooling is kept in this directory. Generated bulk data is excluded
from Git; large Hong Kong proper-name artifacts are stored under
`/Volumes/SSD/hk-proper-nouns` and exposed here through local symlinks.

## Cantonese words

[`cantonesewords/`](cantonesewords/) builds a resumable words.hk text-and-audio
dataset using the `zh-HK-HiuMaanNeural` Edge TTS voice. It validates generated
audio as mono 16 kHz WAV and records it in a two-field manifest.

See [`cantonesewords/README.md`](cantonesewords/README.md) for requirements and
commands.

## Hong Kong proper names

[`hong_kong_proper_nouns/`](hong_kong_proper_nouns/) contains two text dataset
workflows:

- The original flat proper-name scraper combines curated terms with Wikipedia,
  Wikidata, or the `ming030890/wiki-yue-high-quality` Hugging Face dataset.
- The graph extractor treats Cantonese Wikipedia titles as nodes, links title
  mentions, expands equal-weight Hong Kong anchors, and ranks candidates using
  PageRank, community enrichment, incoming mentions, and spoken-title signals.

The graph workflow never generates audio. Its principal generated artifacts
are:

- `seeds_mtr.jsonl`: equal-weight MTR seed titles.
- `wiki_yue_graph/`: nodes, edges, article regions, communities, seed
  promotions, and score diagnostics.
- `proper_names_graph.jsonl`: the final entity list, with one `{"text":"..."}`
  row per entity. Terminal `站` is removed only when the corresponding entity
  without `站` also exists; leading `香港` is removed when the normalized
  keyword is longer than four characters.

See
[`hong_kong_proper_nouns/README.md`](hong_kong_proper_nouns/README.md) for the
sample and full build commands.

## Generated samples

Paths ending in `_sample` are 5,000-article acceptance outputs. Paths ending in
`_sample_determinism` are independent reruns used to verify byte-for-byte
determinism. These generated paths are ignored by Git.
