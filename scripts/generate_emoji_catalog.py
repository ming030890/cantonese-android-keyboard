#!/usr/bin/env python3
"""Build the offline index from AndroidX emoji2-emojipicker 1.6.0 and CLDR 48.

Usage: python3 scripts/generate_emoji_catalog.py picker.aar annotations.json derived.json
CLDR source: https://github.com/unicode-org/cldr-json/tree/48.0.0/cldr-json/cldr-annotations-full/annotations/en
The AAR supplies ordering, categories and complete variant sequences; CLDR supplies
accessible labels and search keywords. No runtime network access is needed.
"""
import json
from pathlib import Path
import sys
import zipfile

CATEGORIES = ['emotions', 'people', 'animals_nature', 'food_drink',
              'travel_places', 'activity', 'objects', 'symbols', 'flags']

def generate(aar, annotations, derived):
    labels = json.loads(Path(annotations).read_text())['annotations']['annotations']
    labels.update(json.loads(Path(derived).read_text())['annotationsDerived']['annotations'])
    labels = {key.replace('\ufe0f', ''): value for key, value in labels.items()}
    rows = []
    with zipfile.ZipFile(aar) as archive:
        for category in CATEGORIES:
            source = f'res/raw/emoji_category_{category}.csv'
            for line in archive.read(source).decode().splitlines():
                variants = list(dict.fromkeys(line.strip().split(',')))
                if not variants[0]:
                    continue
                emoji = variants[0]
                entry = labels.get(emoji.replace('\ufe0f', ''), {})
                name = entry.get('tts', [emoji])[0]
                keywords = ' '.join(entry.get('default', []))
                rows.append('\t'.join([category, emoji, name, keywords, ','.join(variants)]))
    destination = Path(__file__).resolve().parents[1] / 'app/src/main/assets/emoji/catalog.tsv'
    destination.write_text('\n'.join(rows) + '\n')
    print(f'{len(rows)} emoji entries written to {destination}')

if __name__ == '__main__':
    generate(*sys.argv[1:])
