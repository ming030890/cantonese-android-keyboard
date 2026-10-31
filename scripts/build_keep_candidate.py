#!/usr/bin/env python3
"""Freeze the TRAIN-selected tree candidate in an isolated native checkout."""
import argparse,json,os,shutil,subprocess
from pathlib import Path
import autocorrect_eval_v2 as core
ROOT=Path(__file__).resolve().parents[1]
MODULE=r'''//! Small TRAIN-fitted correction/KEEP classifier. No neural runtime dependency.
use serde::Deserialize;
use std::sync::OnceLock;
#[derive(Deserialize)]
struct Node { value:f64, feature:usize, threshold:f64, left:usize, right:usize, leaf:bool, missing_left:bool }
#[derive(Deserialize)]
pub(crate) struct Model { bias:f64, trees:Vec<Vec<Node>>, feature_names:Vec<String>, pub use_vocabulary_case:bool, pub feature_quantization:f64, pub veto_cutoff:f64, pub rescue_cutoff:f64 }
#[derive(Debug)]
pub(crate) struct Evidence { pub proposal:String, pub features:Vec<f64>, pub probability:f64, pub baseline_correction:Option<String> }
pub(crate) fn model()->&'static Model {
    static MODEL:OnceLock<Model>=OnceLock::new();
    MODEL.get_or_init(||serde_json::from_str(include_str!("english_keep_trees.json")).expect("Invalid correction/KEEP trees"))
}
impl Model {
    pub fn probability(&self,features:&[f64])->f64 {
        assert_eq!(features.len(),self.feature_names.len());
        let mut value=self.bias;
        for tree in &self.trees {
            let mut index=0;
            loop {let node=&tree[index];if node.leaf {value+=node.value;break;}
                let x=features[node.feature];index=if if x.is_nan(){node.missing_left}else{x<=node.threshold}{node.left}else{node.right};}
        }
        1./(1.+(-value.clamp(-700.,700.)).exp())
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn embedded_trees_are_finite_acyclic_and_dimensioned() {
        let m=model();assert_eq!(m.feature_names.len(),if m.use_vocabulary_case{52}else{44});
        assert!(m.bias.is_finite() && (0. ..=1.).contains(&m.veto_cutoff) && m.rescue_cutoff>=m.veto_cutoff);
        for tree in &m.trees {assert!(!tree.is_empty());for (i,n) in tree.iter().enumerate() {
            assert!(n.value.is_finite() && n.threshold.is_finite());
            if !n.leaf {assert!(n.feature<m.feature_names.len() && n.left>i && n.right>i && n.left<tree.len() && n.right<tree.len());}
        }}
        assert!((0. ..=1.).contains(&m.probability(&vec![0.;m.feature_names.len()])));
    }
}
'''
CASE_METHOD=r'''
    fn vocabulary_case_features(&self,word:&str)->[f64;4] {
        let Ok(index)=self.weights.spellings.binary_search_by(|(w,_)|w.as_str().cmp(word)) else{return [0.;4];};
        let ids=&self.weights.spellings[index].1;let mut lower=false;let mut title=false;let mut upper=false;
        for &id in ids {let token=self.weights.tokens[id].as_bytes();
            lower|=token.iter().all(|c|c.is_ascii_lowercase());
            title|=token.first().is_some_and(|c|c.is_ascii_uppercase()) && token.iter().skip(1).all(|c|c.is_ascii_lowercase());
            upper|=token.iter().all(|c|c.is_ascii_uppercase());
        }
        [f64::from(u8::from(lower)),f64::from(u8::from(title)),f64::from(u8::from(upper)),f64::from(u8::from(!ids.is_empty() && !lower))]
    }
'''
GATE=r'''
        let mut before=EnglishSuggestions {candidates:ranked.iter().take(8).cloned().collect(),correction,known,literal_score,apostrophe_evidence:vec![],keep_evidence:None};
        let mut keep_evidence=None;
        if let Some((proposal,mut features))=self.weights.channel.diagnostic(left,&original,&logp,&before)? {
            let policy=crate::english_keep::model();
            if policy.use_vocabulary_case {
                features.extend(self.vocabulary_case_features(&original.to_ascii_lowercase()));
                features.extend(self.vocabulary_case_features(&proposal));
            }
            for value in &mut features {*value=(*value*policy.feature_quantization).round()/policy.feature_quantization;}
            let probability=policy.probability(&features);let baseline_correction=before.correction.clone();
            if before.correction.is_some() {
                if probability<policy.veto_cutoff {before.correction=None;}
            }else if probability>=policy.rescue_cutoff && !(proposal.starts_with(&input) && proposal.len()>input.len()+1) {
                before.correction=Some(proposal.clone());
                if let Some(i)=ranked.iter().position(|r|r.word.eq_ignore_ascii_case(&proposal)){before.correction=Some(ranked[i].word.clone());ranked[..=i].rotate_right(1);}else{
                    ranked.insert(0,EnglishSuggestion {word:proposal.clone(),score:ranked.first().map(|r|r.score).unwrap_or(0.),edits:crate::cnn_spelling::distance(input.as_bytes(),proposal.as_bytes(),4).unwrap_or(4),completed_chars:0});
                }
            }
            keep_evidence=Some(crate::english_keep::Evidence {proposal,features,probability,baseline_correction});
        }
        let correction=before.correction;
'''
DIAGNOSTIC=r'''    pub fn acceptance_diagnostic(&mut self,left:&str,typed:&str)->Result<serde_json::Value,String> {
        let mut prediction=self.english_suggestions(left,typed,8)?;
        Ok(match prediction.keep_evidence.take() {
            Some(e)=>serde_json::json!({"prediction":prediction,"eligible":true,"proposal":e.proposal,"features":e.features,"probability":e.probability,"baseline_correction":e.baseline_correction}),
            None=>serde_json::json!({"prediction":prediction,"eligible":false})
        })
    }
'''

def main():
 root=ROOT/'build/english-keep-20261031';parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--study',type=Path,default=root/'tree-study-v3');parser.add_argument('--output',type=Path,default=root/'candidate-source-stable');parser.add_argument('--target',default='/private/tmp/keyboard-keep-stable');args=parser.parse_args();report=json.load(open(args.study/'report.json'));assert report['qualified'];source=args.output;shutil.copytree(root/'native-source',source);src=source/'src';shutil.copyfile(args.study/(report['winner']+'.json'),src/'english_keep_trees.json');(src/'english_keep.rs').write_text(MODULE)
 manifest=source/'Cargo.toml';manifest.write_text(manifest.read_text().replace('serde_json = "1.0"','serde_json = { version = "1.0", features = ["float_roundtrip"] }'))
 p=src/'lib.rs';p.write_text(p.read_text().replace('mod english_channel;','mod english_channel;\nmod english_keep;'))
 p=src/'suggestions.rs';s=p.read_text().replace('apostrophe_evidence:vec![] }','apostrophe_evidence:vec![],keep_evidence:None }').replace('                apostrophe_evidence: vec![],','                apostrophe_evidence: vec![],\n                keep_evidence: None,');p.write_text(s)
 p=src/'cnn.rs';s=p.read_text();marker='    pub apostrophe_evidence: Vec<ApostropheEvidence>,\n';assert s.count(marker)==1;s=s.replace(marker,marker+'    #[serde(skip)]\n    pub(crate) keep_evidence: Option<crate::english_keep::Evidence>,\n')
 s=s.replace('        let channel = crate::english_channel::Decoder::new(&spellings, &meta.english_word_ids);','        let channel = crate::english_channel::Decoder::new(&spellings, &meta.english_word_ids);\n        crate::english_keep::model();')
 a=s.index('    pub fn acceptance_diagnostic(');b=s.index('    pub fn english_suggestions(',a);s=s[:a]+CASE_METHOD+DIAGNOSTIC+s[b:]
 s=s.replace('literal_score:None,apostrophe_evidence:vec![]});','literal_score:None,apostrophe_evidence:vec![],keep_evidence:None});')
 marker='        let correction = restored.or(channel).or(correction);\n';assert s.count(marker)==1;s=s.replace(marker,marker+GATE)
 old='Ok(EnglishSuggestions {candidates:ranked,correction,known,literal_score,apostrophe_evidence})';assert s.count(old)==1;s=s.replace(old,'Ok(EnglishSuggestions {candidates:ranked,correction,known,literal_score,apostrophe_evidence,keep_evidence})');p.write_text(s)
 core.write_json(source/'frozen-choice.json',dict(winner=report['winner'],model_sha256=core.digest(src/'english_keep_trees.json'),selection='Highest TRAIN-calibration F0.5; frozen objective, old development, new chat development and known regressions passed before native port',source_sha256=core.digest(__file__),confirmation_opened=False))
 env=dict(os.environ,CARGO_TARGET_DIR=args.target);subprocess.run(['cargo','build','--offline','--manifest-path',str(source/'Cargo.toml'),'--release','--features','android','--lib','--bin','ngram_probe','--bin','suggestion_probe'],env=env,check=True)
if __name__=='__main__':main()
