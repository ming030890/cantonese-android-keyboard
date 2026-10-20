"""Conservative reference-span labels for recorded mobile events.

Only exact preceding-context matches are retained. A unique nearest reference
span of up to three words resolves swallowed spaces; ambiguous alignments are
excluded. Candidate generation never receives these labels.
"""
import re

WORD = re.compile(r"[A-Za-z]+(?:'[A-Za-z]+)*")


def osa(a, b):
    previous = list(range(len(a)+1))
    before = previous
    for i, letter in enumerate(b):
        current = [i+1]
        for j, observed in enumerate(a):
            value = min(previous[j+1]+1, current[j]+1, previous[j]+(letter != observed))
            if i and j and letter == a[j-1] and b[i-1] == observed:
                value = min(value, before[j-1]+1)
            current.append(value)
        before, previous = previous, current
    return previous[-1]


def reference_target(case, reference):
    left = case["left"]
    if not reference.lower().startswith(left.lower()):
        return None
    suffix = reference[len(left):]
    matches = list(WORD.finditer(suffix))[:3]
    if not matches or matches[0].start() != 0 or matches[0][0].lower() != case["target"]:
        return None
    possibilities = []
    for i, match in enumerate(matches):
        if i and not suffix[matches[i-1].end():match.start()].isspace():
            break
        target = " ".join(m[0].lower() for m in matches[:i+1])
        possibilities.append((osa(case["typed"].lower(), target), target))
    possibilities.sort()
    if possibilities[0][0] > 3 or (len(possibilities) > 1 and possibilities[0][0] == possibilities[1][0]):
        return None
    return possibilities[0][1]
