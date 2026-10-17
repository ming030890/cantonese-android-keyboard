"""Compile the pinned SCOWL dictionary's explicit affix rules, never guessed endings.

The caller supplies canonical TRAIN word counts. Correctness and frequency have
separate provenance; unknown words remain eligible literal input at runtime.
"""
import argparse,json,re,hashlib,subprocess
from pathlib import Path

ASCII=re.compile("[a-z]+(?:'[a-z]+)*")

def expand(dic,aff):
    rules={};cross={}
    for raw in aff.read_text().splitlines():
        parts=raw.split()
        if not parts or parts[0] not in ('PFX','SFX'):continue
        kind,flag=parts[:2]
        if len(parts)==4:
            assert parts[2] in ('Y','N');cross[kind,flag]=parts[2]=='Y';rules[kind,flag]=[]
        elif len(parts)>=5:
            strip,add,condition=parts[2:5]
            assert '/' not in add,'Continuation flags need explicit implementation'
            rules[kind,flag].append(('' if strip=='0' else strip,'' if add=='0' else add,
                re.compile(('^'+condition) if kind=='PFX' else (condition+'$'))))
        else:raise ValueError('Malformed affix')
    def apply(word,kind,rule):
        strip,add,condition=rule
        if not condition.search(word):return None
        if kind=='PFX':
            if strip and not word.startswith(strip):return None
            return add+word[len(strip):]
        if strip and not word.endswith(strip):return None
        return (word[:-len(strip)] if strip else word)+add
    valid=set();suggest=set()
    for raw in dic.read_text().splitlines()[1:]:
        entry=raw.split('\t')[0].split(' ')[0];original,_,flags=entry.partition('/');word=original.lower()
        if not ASCII.fullmatch(word) or 'c' in flags:continue
        forms={word}
        # Letter names/abbreviations are valid literal input, but don't create
        # ordinary-word evidence (k/S -> ks). The ordinary words a and i still
        # follow the source's explicit affix flags: those also supply as/is.
        if len(word)==1 and word not in ('a','i'):
            valid.add(original)
            continue
        prefixes=[(f,r) for f in flags for r in rules.get(('PFX',f),[])]
        suffixes=[(f,r) for f in flags for r in rules.get(('SFX',f),[])]
        for flag,rule in prefixes:
            value=apply(word,'PFX',rule)
            if value:forms.add(value)
        for flag,rule in suffixes:
            value=apply(word,'SFX',rule)
            if value:forms.add(value)
            if value and cross['SFX',flag]:
                for pf,pr in prefixes:
                    if cross['PFX',pf]:
                        joined=apply(value,'PFX',pr)
                        if joined:forms.add(joined)
        forms={w for w in forms if ASCII.fullmatch(w) and len(w)<=64}
        if len(original)>1 and original.isupper():
            # KS is an acronym, not a lowercase English spelling. Preserve its
            # actual case without adding "ks" as an English correction target.
            valid.update(original+form[len(word):] for form in forms if form.startswith(word))
        else:
            valid.update(forms)
            if '!' not in flags:suggest.update(forms)
    return valid,suggest

def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--dictionary',type=Path,required=True);parser.add_argument('--affix',type=Path,required=True)
    parser.add_argument('--counts',type=Path,required=True);parser.add_argument('--output',type=Path,required=True);parser.add_argument('--packer',type=Path,required=True)
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    valid,suggest=expand(args.dictionary,args.affix)
    counts={}
    for line in args.counts.read_text().splitlines():
        w,n=line.split();counts[w]=int(n)
    source=args.output/'candidates.txt';source.write_text(''.join(f'{w} {max(1,counts.get(w,0))}\n' for w in sorted(suggest)))
    (args.output/'valid_words.txt').write_text(''.join(w+'\n' for w in sorted(valid)))
    subprocess.run([str(args.packer),str(source),str(args.output/'en-v2.bin')],check=True)
    meta=dict(builder_sha256=sha(Path(__file__)),dictionary_sha256=sha(args.dictionary),affix_sha256=sha(args.affix),counts_sha256=sha(args.counts),
        candidate_source_sha256=sha(source),candidate_dictionary_sha256=sha(args.output/'en-v2.bin'),validity_sha256=sha(args.output/'valid_words.txt'),
        valid_forms=len(valid),suggested_forms=len(suggest),frequency_scope='Existing canonical TRAIN-only English counts, floor1 for valid unobserved forms',
        derived_resources_license='See pinned SCOWL README_en_US.txt; no user-message terms or fabricated frequencies',test_read=False)
    (args.output/'vocabulary.json').write_text(json.dumps(meta,indent=2)+'\n');print(json.dumps(meta),flush=True)

if __name__=='__main__':main()
