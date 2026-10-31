#!/usr/bin/env python3
"""Build isolated native proposal evidence without changing accepted source."""
import json,os,shutil,subprocess
from pathlib import Path
import autocorrect_eval_v2 as core
ROOT=Path(__file__).resolve().parents[1]
FEATURES=['accepted_before','typed_dictionary','typed_neural','typed_length','candidate_length','length_delta','osa_edits','channel_cost','cost_per_char','literal_log_frequency','candidate_log_frequency','literal_context_gain','candidate_context_gain','candidate_minus_keep','candidate_posterior_logodds','keep_posterior_logodds','candidate_channel_rank','candidate_minus_channel_best','channel_best_runner_gap','english_log_mass','literal_lm_logprob','candidate_lm_logprob','candidate_is_channel_best','original_first_upper','original_all_upper','original_internal_upper','sentence_start','prior_word_count','last_word_length','last_word_first_upper','typed_frequency_observed','old_candidate_score','old_top_score','old_runner_score','old_top_runner_gap','candidate_completion','old_known','old_literal_score','candidate_first_changed','candidate_last_changed','candidate_frequency_minus_literal','neural_candidate','old_dictionary_typed','old_dictionary_candidate']
METHOD=r'''
    /// Read-only proposal evidence for fitting a complementary decision model.
    pub(crate) fn diagnostic(&self,left:&str,original:&str,logp:&[f32],before:&crate::cnn::EnglishSuggestions)->Result<Option<(String,Vec<f64>)>,String> {
        let input=original.to_ascii_lowercase();
        if left.trim().is_empty() || left.chars().any(|c|matches!(c,'\u{3400}'..='\u{9fff}'|'\u{20000}'..='\u{323af}')) ||
            !(2..=32).contains(&input.len()) || !input.bytes().all(|c|c.is_ascii_lowercase()) {return Ok(None);}
        let pairs=self.proposals(&input)?;if pairs.is_empty(){return Ok(None);}
        let mass=logsum(self.english_ids.iter().map(|&i|logp[i] as f64));
        let gain=|ids:&[usize],prior:f64|((logsum(ids.iter().map(|&i|logp[i] as f64))-mass-prior+self.metadata.english_log_mass) as f32) as f64;
        let ti=self.words.binary_search_by(|w|w.text.as_str().cmp(&input)).ok();
        let mut lf=ti.map(|i|self.words[i].log_frequency.exp());
        let (lg,llp)=if let Some((ids,prior))=self.neural.get(&input) {
            if lf.is_none(){lf=Some((prior-self.metadata.english_log_mass).exp()*self.metadata.total_frequency);}
            (gain(ids,*prior),logsum(ids.iter().map(|&i|logp[i] as f64)))
        }else{(0.,-30.)};
        let literal_log_frequency=lf.unwrap_or(self.policy.unknown_floor).ln();
        let keep=literal_log_frequency+self.policy.beta*lg;
        let score_word=|id:usize,cost:f64| {let w=&self.words[id];let g=w.prior.filter(|_|!w.ids.is_empty()).map(|p|gain(&w.ids,p)).unwrap_or(0.);((w.log_frequency as f32) as f64+self.policy.beta*g-self.policy.tau*cost,g)};
        let scores:Vec<_>=pairs.iter().map(|&(id,cost)|(id,score_word(id,cost).0)).collect();
        let &(best_id,best_score)=scores.iter().max_by(|a,b|a.1.total_cmp(&b.1).then(b.0.cmp(&a.0))).unwrap();
        let proposal=before.correction.as_deref().unwrap_or(&self.words[best_id].text).to_ascii_lowercase();
        if proposal==input || !proposal.bytes().all(|c|c.is_ascii_lowercase()){return Ok(None);}
        let Ok(id)=self.words.binary_search_by(|w|w.text.cmp(&proposal)) else{return Ok(None);};
        let w=&self.words[id];let cost=self.channel.cost(input.as_bytes(),w.text.as_bytes());let (score,cg)=score_word(id,cost);
        let competitors=logsum(std::iter::once(keep).chain(scores.iter().filter(|v|v.0!=id).map(|v|v.1)));
        let keep_other=logsum(scores.iter().map(|v|v.1).chain((!scores.iter().any(|v|v.0==id)).then_some(score)));
        let rank=scores.iter().filter(|v|v.1>score).count() as f64;
        let runner=scores.iter().filter(|v|v.0!=best_id).map(|v|v.1).fold(f64::NEG_INFINITY,f64::max);
        let clp=if w.ids.is_empty(){-30.}else{logsum(w.ids.iter().map(|&i|logp[i] as f64))};
        let flag=|b:bool|f64::from(u8::from(b));let last=left.split_whitespace().last().unwrap_or("");
        let end=left.trim_end().chars().last();let sentence_start=end.is_none_or(|c|".!?。！？".contains(c));
        let oldrow=before.candidates.iter().find(|r|r.word.eq_ignore_ascii_case(&proposal));
        let oldtop=before.candidates.first().map(|r|r.score as f64).unwrap_or(-30.);let oldrunner=before.candidates.get(1).map(|r|r.score as f64).unwrap_or(-30.);
        let f=vec![flag(before.correction.is_some()),flag(ti.is_some()),flag(self.neural.contains_key(&input)),input.len() as f64,proposal.len() as f64,proposal.len() as f64-input.len() as f64,
            crate::cnn_spelling::distance(input.as_bytes(),proposal.as_bytes(),4).unwrap_or(5) as f64,cost,cost/input.len() as f64,literal_log_frequency,w.log_frequency,lg,cg,score-keep,score-competitors,keep-keep_other,rank,score-best_score,best_score-runner,mass,llp,clp,flag(id==best_id),
            flag(original.as_bytes()[0].is_ascii_uppercase()),flag(original.bytes().all(|c|c.is_ascii_uppercase())),flag(original.bytes().skip(1).any(|c|c.is_ascii_uppercase())),flag(sentence_start),left.split_whitespace().count() as f64,last.len() as f64,flag(last.as_bytes().first().is_some_and(|c|c.is_ascii_uppercase())),flag(lf.is_some()),
            oldrow.map(|r|r.score as f64).unwrap_or(oldtop),oldtop,oldrunner,oldtop-oldrunner,oldrow.map(|r|r.completed_chars as f64).unwrap_or(0.),flag(before.known),before.literal_score.map(f64::from).unwrap_or(-30.),flag(input.as_bytes().first()!=proposal.as_bytes().first()),flag(input.as_bytes().last()!=proposal.as_bytes().last()),w.log_frequency-literal_log_frequency,flag(!w.ids.is_empty()),flag(crate::cnn_spelling::dictionary_contains(&input)),flag(crate::cnn_spelling::dictionary_contains(&proposal))];
        let f=f.into_iter().map(|v|if v.is_finite(){v.clamp(-1000.,1000.)}else{0.}).collect();
        Ok(Some((proposal,f)))
    }
'''

def main():
 root=ROOT/'build/english-keep-20261031';out=root/'native-source';out.mkdir(exist_ok=False);native=ROOT.parent/'QwenASR/crates/cantonese-ngram'
 shutil.copytree(native/'src',out/'src')
 for name in ('Cargo.toml','Cargo.lock'):shutil.copyfile(native/name,out/name)
 path=out/'src/english_channel.rs';s=path.read_text();marker='    pub fn correction(&self,left:&str,input:&str,logp:&[f32])->Result<Option<String>,String> {';assert s.count(marker)==1;path.write_text(s.replace(marker,METHOD+'\n'+marker))
 path=out/'src/cnn.rs';s=path.read_text();marker='    pub fn english_suggestions(&mut self,left:&str,typed:&str,limit:usize)->Result<EnglishSuggestions,String> {';assert s.count(marker)==1
 method='''    pub fn acceptance_diagnostic(&mut self,left:&str,typed:&str)->Result<serde_json::Value,String> {
        let before=self.english_suggestions(left,typed,8)?;
        let context=self.context(left);let logp=self.log_probabilities(&context)?;
        let evidence=self.weights.channel.diagnostic(left,typed,&logp,&before)?;
        Ok(match evidence {Some((proposal,features))=>serde_json::json!({"prediction":before,"proposal":proposal,"features":features,"eligible":true}),None=>serde_json::json!({"prediction":before,"eligible":false})})
    }
''';path.write_text(s.replace(marker,method+marker))
 path=out/'src/bin/ngram_probe.rs';s=path.read_text();old='            serde_json::to_value(lm.english_suggestions_with_touch(left,typed,limit,touch.as_ref()).map_err(io::Error::other)?)?';new='''            if x["acceptance_diagnostic"].as_bool().unwrap_or(false) {
                if let NeuralLanguageModel::Cnn(model)=&mut lm {model.acceptance_diagnostic(left,typed).map_err(io::Error::other)?}else{return Err("CNN required".into());}
            }else{serde_json::to_value(lm.english_suggestions_with_touch(left,typed,limit,touch.as_ref()).map_err(io::Error::other)?)?}''';assert s.count(old)==1;path.write_text(s.replace(old,new))
 core.write_json(root/'diagnostic-features.json',dict(features=FEATURES,source_sha256=core.digest(__file__),accepted_source={str(p.relative_to(native)):core.digest(p) for p in [native/'src/cnn.rs',native/'src/english_channel.rs']}))
 env=dict(os.environ,CARGO_TARGET_DIR='/private/tmp/keyboard-keep-diagnostics');subprocess.run(['cargo','build','--offline','--manifest-path',str(out/'Cargo.toml'),'--release','--features','android','--bin','ngram_probe'],env=env,check=True)
if __name__=='__main__':main()
