#!/usr/bin/env python3
"""Full valid-word proposals with TRAIN word priors and typed-letter likelihoods."""
import json,math,re,subprocess
from pathlib import Path
import autocorrect_eval_v2 as core
ROOT=Path(__file__).resolve().parents[1]

def main():
 out=ROOT/'build/english-noisy-channel-20261030';out.mkdir(exist_ok=False);source=out/'source';source.mkdir()
 counts_file=Path('/Volumes/SSD/cantonese_ngram/experiments/spelling-vocabulary-v77/candidates.txt');counts={w:float(n) for w,n in (line.split() for line in counts_file.read_text().splitlines()) if len(w)<=32}
 total=sum(counts.values());priors=json.load(open(ROOT/'build/english-listwise-20261030/context-features/word-priors.json'))['prior'];mass=sum(math.exp(v) for v in priors.values())
 gb=ROOT/'build/english-lexical-audit-20261030/raw/en_GB.dic';added=0
 for line in gb.read_text(encoding='utf-8-sig').splitlines()[1:]:
  word=line.split('/')[0].split()[0].lower() if line else ''
  if re.fullmatch("[a-z]+(?:'[a-z]+)*",word) and len(word)<=32 and word not in counts:
   counts[word]=max(1.,math.exp(priors[word])*total/mass) if word in priors else 1.;added+=1
 names=sorted(counts);(out/'lexicon.tsv').write_text(''.join(w+'\t'+str(counts[w])+'\n' for w in names));core.write_json(out/'word-priors.json',dict(counts=counts,total=total,neural_word_mass=mass,neural_priors=priors))
 src=ROOT.parent/'QwenASR/crates/cantonese-ngram/src/spelling_index.rs';text=src.read_text().replace('crate::suggestion_request::check()?;','');(source/'spelling_index.rs').write_text(text)
 (source/'main.rs').write_text(r'''mod spelling_index;
use std::{io::{self,BufRead},collections::HashMap};
fn main(){let args:Vec<_>=std::env::args().collect();let data=std::fs::read_to_string(&args[1]).unwrap();let rows:Vec<_>=data.lines().map(|l|l.split_once('\t').unwrap()).collect();let words:Vec<_>=rows.iter().map(|r|r.0.to_owned()).collect();let counts:Vec<f64>=rows.iter().map(|r|r.1.parse().unwrap()).collect();let index=spelling_index::SpellingIndex::new(words.iter().map(String::as_str));let mut prefixes:HashMap<String,f64>=HashMap::new();for(w,c)in words.iter().zip(&counts){for n in 1..=w.len().saturating_sub(2){*prefixes.entry(w[..n].to_owned()).or_default()+=c;}}
for line in io::stdin().lock().lines(){let input=line.unwrap().replace('’',"'").to_ascii_lowercase();if input.is_empty()||input.len()>32||!input.bytes().all(|b|b.is_ascii_lowercase()||b==b'\''){println!("0\t");continue;}let radius=if input.len()<=2{1}else if input.len()==3{2}else if input.len()<=5{3}else{4};let hits=index.matches(&input,radius,false).unwrap();let output=hits.iter().map(|(i,d)|format!("{i}:{d}")).collect::<Vec<_>>().join(",");println!("{}\t{}",prefixes.get(&input).unwrap_or(&0.),output);}}
''')
 subprocess.run(['rustc','--edition=2021','-O',str(source/'main.rs'),'-o',str(out/'dictionary_candidates')],check=True)
 core.write_json(out/'protocol.json',dict(schema='full-lexicon-noisy-channel-v1',lexicon_words=len(names),uk_base_words_added=added,source_counts_sha256=core.digest(counts_file),source_dictionary_sha256=core.digest(gb),imputed_priors='UK roots absent from old table use empty-context shared-LM word probability as pseudocount, floor1; existing TRAIN counts remain unchanged',source_sha256=core.digest(__file__),channel_sha256=core.digest(ROOT/'build/english-listwise-20261030/character-channel/model.json'),confirmation_used=False))
 print('LEXICON',len(names),'UK ROOTS',added,flush=True)
if __name__=='__main__':main()
