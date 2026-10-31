#!/usr/bin/env python3
"""Build a context-specific policy in an isolated crate; production unchanged."""
import argparse,os,shutil,subprocess
from pathlib import Path
from experiment_conditional_english_scores import with_radius_candidate
ROOT=Path(__file__).resolve().parents[1]
def main():
    p=argparse.ArgumentParser();p.add_argument('--policy',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    source=a.output.resolve()/'native-source';source.mkdir(parents=True,exist_ok=False)
    native=ROOT.parent/'QwenASR/crates/cantonese-ngram'
    shutil.copytree(native/'src',source/'src')
    for n in ('Cargo.toml','Cargo.lock'):shutil.copyfile(native/n,source/n)
    shutil.copyfile(a.policy,source/'src/english_mixed_acceptance.json')
    path=source/'src/cnn.rs';s=with_radius_candidate(path.read_text())
    s=s.replace('    input: String,','    input: String,\n    original_case: [f64; 3],')
    s=s.replace('EnglishCandidatePool { input, pool, known, literal_ids, merge_splits, apostrophes }', 'EnglishCandidatePool { input, original_case, pool, known, literal_ids, merge_splits, apostrophes }')
    marker='        let apostrophes = crate::cnn_spelling::apostrophe_forms(&input).collect();'
    s=s.replace(marker,'''        let original_case = [
            if typed.as_bytes().first().is_some_and(u8::is_ascii_uppercase){1.}else{0.},
            if typed.bytes().filter(u8::is_ascii_alphabetic).all(|b|b.is_ascii_uppercase()){1.}else{0.},
            if typed.chars().skip(1).any(|c|c.is_ascii_uppercase()){1.}else{0.},
        ];
'''+marker)
    marker='        let input_is_lower = input.bytes().all(|b| b.is_ascii_lowercase());'
    s=s.replace(marker,'''        let mixed=left.chars().any(|c|matches!(c as u32,0x3400..=0x9fff|0x20000..=0x323af));
        let mass=if mixed {logp.iter().enumerate().filter(|(i,_)|self.weights.words.contains(i))
            .map(|(_,v)|(*v as f64).exp()).sum::<f64>().max(1e-30).ln()}else{0.};
'''+marker)
    marker='r.word.as_str())), literal_score))'
    assert s.count(marker)==1;s=s.replace(marker,'r.word.as_str())), literal_score, mixed, mass, original_case))');path.write_text(s)
    path=source/'src/cnn_spelling.rs';s=path.read_text()
    s=s.replace('    literal: Option<f32>,','    literal: Option<f32>,\n    mixed: bool,\n    english_log_mass: f64,\n    original_case: [f64;3],')
    marker='    if word.eq_ignore_ascii_case(typed) && word != typed {'
    s=s.replace(marker,'''    static MIXED: std::sync::OnceLock<AcceptancePolicy> = std::sync::OnceLock::new();
    let policy = if mixed {MIXED.get_or_init(||serde_json::from_str(include_str!("english_mixed_acceptance.json")).expect("Invalid mixed policy"))}else{policy};
'''+marker)
    s=s.replace('policy.means.len() == 22','policy.means.len() >= 22')
    marker='''    ];

    let mut hidden = policy.b1;'''
    assert s.count(marker)==1;s=s.replace(marker,'''        english_log_mass,
        original_case[0],original_case[1],original_case[2],
    ];

    let mut hidden = policy.b1;''');path.write_text(s)
    subprocess.run(['cargo','build','--offline','--manifest-path',str(source/'Cargo.toml'),'--release','--features','android','--bin','ngram_probe','--bin','suggestion_probe','--lib'],env=dict(os.environ,CARGO_TARGET_DIR=str(a.output.resolve()/'target')),check=True)
if __name__=='__main__':main()
