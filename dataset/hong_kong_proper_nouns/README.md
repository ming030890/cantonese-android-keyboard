# Hong Kong Proper Nouns Dataset

This folder contains tooling for building a Hong Kong proper-noun lexicon for Cantonese ASR / keyboard biasing. It targets names that are easy for a recognizer to miss: places, districts, MTR stations, public bodies, brands, companies, schools, celebrities, politicians, artists, athletes, and other Hong Kong-specific entities.

## Files

- `scrape_hk_proper_nouns.py` fetches article titles from a Hugging Face Cantonese Wikipedia dataset by default. It can also fetch category members from Wikipedia / Cantonese Wikipedia and structured entities from Wikidata.
- `seed_terms.tsv` contains a small hand-curated starter list for common Hong Kong terms.
- `mobile_terms.tsv` contains high-value mobile voice-input terms: apps, transport, residential estates, malls, hospitals, schools, government services, finance, food/shop brands, media, and events.
- `out/` is generated output and is intentionally ignored by git.

## Usage

Run from the project root:

```sh
python3 dataset/hong_kong_proper_nouns/scrape_hk_proper_nouns.py
```

The default source is `ming030890/wiki-yue-high-quality`, which exposes a `title` column. The script only writes the article title as the term; the article text is used only as a lightweight Hong Kong relevance filter.

Download the dataset to SSD and scrape locally:

```sh
mkdir -p /Volumes/SSD/hk-proper-nouns/wiki-yue-high-quality
hf download ming030890/wiki-yue-high-quality data/train-00000-of-00001.parquet \
  --type dataset \
  --local-dir /Volumes/SSD/hk-proper-nouns/wiki-yue-high-quality
/opt/homebrew/Caskroom/miniconda/base/bin/python \
  dataset/hong_kong_proper_nouns/scrape_hk_proper_nouns.py \
  --sources local-parquet \
  --local-parquet /Volumes/SSD/hk-proper-nouns/wiki-yue-high-quality/data/train-00000-of-00001.parquet \
  --limit 100000 \
  --output-dir /Volumes/SSD/hk-proper-nouns/out
```

Generate a stricter cleaned title list that removes digit-heavy and event/date/list-like titles:

```sh
/opt/homebrew/Caskroom/miniconda/base/bin/python \
  dataset/hong_kong_proper_nouns/scrape_hk_proper_nouns.py \
  --sources local-parquet \
  --local-parquet /Volumes/SSD/hk-proper-nouns/wiki-yue-high-quality/data/train-00000-of-00001.parquet \
  --limit 100000 \
  --post-filter clean \
  --output-dir /Volumes/SSD/hk-proper-nouns/out-clean
```

Generate only the curated mobile/core terms:

```sh
python3 dataset/hong_kong_proper_nouns/scrape_hk_proper_nouns.py \
  --seed-only \
  --output-dir /Volumes/SSD/hk-proper-nouns/out-mobile-core
```

Fetch more Hugging Face titles:

```sh
python3 dataset/hong_kong_proper_nouns/scrape_hk_proper_nouns.py \
  --sources huggingface \
  --hf-scan-rows 108182 \
  --limit 10000
```

Keep every Cantonese Wikipedia title, without the Hong Kong filter:

```sh
python3 dataset/hong_kong_proper_nouns/scrape_hk_proper_nouns.py \
  --sources huggingface \
  --no-hk-filter \
  --hf-scan-rows 108182 \
  --limit 100000
```

Fetch deeper Wikipedia subcategories with conservative throttling:

```sh
python3 dataset/hong_kong_proper_nouns/scrape_hk_proper_nouns.py --limit 1000 --category-depth 2 --sleep 1.5
```

Include Wikidata structured entities as an enrichment source:

```sh
python3 dataset/hong_kong_proper_nouns/scrape_hk_proper_nouns.py --sources all
```

Useful options:

```sh
python3 dataset/hong_kong_proper_nouns/scrape_hk_proper_nouns.py \
  --sources huggingface,wikipedia \
  --limit 1000 \
  --hf-scan-rows 50000 \
  --category-depth 2 \
  --sleep 1.5 \
  --output-dir dataset/hong_kong_proper_nouns/out
```

The Wikidata SPARQL endpoint sometimes returns strong `429` rate limits. For bulk collection, prefer the default Hugging Face title source first, then run `--sources wikipedia` or `--sources wikidata` later as separate enrichment passes.

## Outputs

- `hk_proper_nouns.jsonl`: one JSON record per entity.
- `hk_proper_nouns.tsv`: spreadsheet-friendly records.
- `terms.txt`: deduplicated terms only, one per line.
- `stats.json`: counts by category and source.

Each record includes:

- `term`: the noun/name text.
- `category`: broad bucket such as `place`, `celebrity`, `organization`, `transport`, `app`, `hospital`, `school`, or `shop_brand`.
- `lang`: source label language if known.
- `source`: source system, e.g. `huggingface`, `wikidata`, or `wikipedia`.
- `source_url`: URL for traceability.

Review the generated output before using it in production. Public knowledge bases contain aliases, old names, disambiguation entries, and occasional noisy category members.
