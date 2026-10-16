# English input preservation

The three upstream files are copied without modification from the pinned
LibreOffice dictionary revision in `provenance.json`. The complete dictionary
copyright, permissions and component notices are in `README_en_US.txt`.

Our modified use is root-only input validation in `EnglishLiteralLexicon.kt`:
discard the numeric header and affix flags, exclude compound-only (`c`) roots,
case-fold ASCII English words with optional apostrophes, and store a set.
Inflected forms are not guessed. No frequencies are assigned and these roots
are never added to correction candidates or used to train the LM.

A recognized input stays unchanged in both the new model decision and the old
boundary fallback. This prevents a sparse suggestion dictionary from treating
valid words such as `typo` as misspellings. It is not a complete English-word
validator or a solution for unknown names and missed corrections.
