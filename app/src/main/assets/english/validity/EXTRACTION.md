# English input preservation

The three upstream files are copied without modification from the pinned
LibreOffice dictionary revision in `provenance.json`. The complete dictionary
copyright, permissions and component notices are in `README_en_US.txt`.

`scripts/build_english_vocabulary.py` applies only the source's explicit affix
flags, strip/add rules and conditions. It excludes compound-only roots and
excludes NOSUGGEST forms from candidate generation. Uppercase acronyms retain
their original case. Letter-name inflections are excluded; the ordinary words
`a` and `i` retain their source affixes. Endings are never guessed.

The generated `valid_words.txt` supplies spelling evidence to
`EnglishLiteralLexicon.kt`; `compiled_vocabulary.json` binds the builder, pinned
sources, TRAIN counts and compiled dictionary hashes. These forms also supply
the separately packed correction-candidate vocabulary. Observed frequencies come
from canonical TRAIN-only English counts, with floor1 for otherwise valid forms.
The dictionary is not LM training text and no Chinese conversion is performed.

The contextual internal policy treats recognized words as evidence rather than
a categorical veto: strong contextual decisions can correct real-word typos.
Older policies retain their preservation behavior. The conservative legacy path
still preserves recognized words. This is not a complete word validator or an
unknown-name detector; clean-word and unfamiliar-name evaluation remains required.
