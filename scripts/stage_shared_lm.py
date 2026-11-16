"""Stage one verified experimental CNN and archive/remove obsolete LM assets."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import struct


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def stage(project,source,verification,backup,fusion_json=None):
    project,source,backup=map(Path,(project,source,backup))
    proof=json.loads(Path(verification).read_text())
    if not proof['passed'] or proof['model_sha256']!=sha(source):
        raise ValueError('Actual native verification required before replacing assets')
    raw=source.read_bytes()
    magic=raw[:8]
    if magic!=b'KBLM03\0\0':raise ValueError('Expected shared KBLM03 bundle')
    size=struct.unpack('<I',raw[8:12])[0];meta=json.loads(raw[12:12+size])
    assets=project/'app/src/main/assets'
    native=project.parent/'QwenASR/crates/cantonese-ngram/src'
    # Preserve the exact previous local assets before cleanup; never overwrite it.
    if not backup.exists():
        backup.mkdir(parents=True)
        for directory in ('typing','english','language_model'):
            if (assets/directory).exists():shutil.copytree(assets/directory,backup/directory)
        for file in ('selected_model.json',):
            shutil.copy2(project/'config/language_model'/file,backup/file)
        count=assets/'handwriting/lm_csr.bin'
        if count.exists():shutil.copy2(count,backup/'lm_csr.bin')
    prev_selected_path=project/'config/language_model/selected_model.json'
    prev_selected=json.loads(prev_selected_path.read_text()) if prev_selected_path.exists() else {}
    target=assets/'language_model/model.cng'
    target.parent.mkdir(parents=True,exist_ok=True)
    tmp=target.with_suffix('.tmp');shutil.copy2(source,tmp);tmp.replace(target)
    if fusion_json is not None:
        fusion=json.loads(Path(fusion_json).read_text())
        if fusion['neural_model_sha256']!=sha(source):
            raise ValueError('Supplied fusion JSON does not match staged model SHA-256')
        fusion['inputs']=dict(
            handwriting_model_sha256=sha(assets/'handwriting/model.safetensors'),
            labels_sha256=sha(assets/'handwriting/labels.txt'),
            counts_sha256=hashlib.sha256(b'').hexdigest(),
        )
    else:
        fusion=dict(version=1,weights=dict(unigram=0,bigram=0,neural=1.),
            neural_model_sha256=sha(source),calibrated=False,internal_trial=True,
            selection='Explicit unit fusion weight for shared causal LM integration',
            inputs=dict(handwriting_model_sha256=sha(assets/'handwriting/model.safetensors'),
                        labels_sha256=sha(assets/'handwriting/labels.txt'),counts_sha256=hashlib.sha256(b'').hexdigest()))
    fusion_source=project/'config/language_model/handwriting_fusion.json'
    fusion_source.write_text(json.dumps(fusion,indent=2)+'\n')
    shutil.copy2(fusion_source,assets/'language_model/handwriting_fusion.json')
    config={k:meta[k] for k in ('vocab_size','head_vocab_size','d_embed_head','d_embed_tail','d_model','d_ff','n_blocks','block_types','conv_kernel','n_q_heads','n_kv_heads','d_head','window_size','context_tokens','right_context_tokens')}
    fmt='KBLM_INT8_V3'
    english_policy=dict(prev_selected.get('english_policy',dict(
        candidate_source='TRAIN-attested LM vocabulary plus 169575 dictionary forms in the complementary English typing channel',
        implementation='../QwenASR/crates/cantonese-ngram/src/cnn.rs::english_suggestions',
        parameter_source='Native 22-24-1 FFN warm-started with authentic-context short-word supervision; literal-score input disabled',
        backspace_undo=True,production_qualified=False)))
    acceptance_file=native/'english_acceptance.json'
    if acceptance_file.exists():
        english_policy['acceptance_source']='../QwenASR/crates/cantonese-ngram/src/english_acceptance.json'
        english_policy['acceptance_sha256']=sha(acceptance_file)
    if 'noisy_channel' in english_policy:
        ch_dir=native/'english_channel_data'
        english_policy['noisy_channel']=dict(english_policy['noisy_channel'])
        english_policy['noisy_channel']['files']={
            name:sha(ch_dir/name) for name in ('channel.json','lexicon.tsv','metadata.json','policy.json','priors.tsv')
        }
    selection=dict(format=fmt,model_source=str(source),model_sha256=sha(source),
        model_bytes=target.stat().st_size,config=config,
        fusion_source=str(fusion_source.relative_to(project)),fusion_sha256=sha(fusion_source),
        neural_short_contexts=True,count_table_packaged=False,experimental=True,production_approved=False,
        selection='One shared Cantonese/English/Quick/Swipe/Handwriting LM; English spelling uses its vocabulary plus high-confidence learned typing-channel dictionary proposals',
        english_policy=english_policy,
        qualification='Experimental shared LM staged with full native recalibration; see qualification report',
        native_verification=str(verification),old_assets_backup=str(backup))
    prev_selected_path.write_text(json.dumps(selection,indent=2)+'\n')
    # No remaining production reader uses these spelling models/tables/policies.
    removed=[]
    for directory in ('typing','english'):
        if (assets/directory).exists():
            shutil.rmtree(assets/directory);removed.append(directory)
    count=assets/'handwriting/lm_csr.bin'
    if count.exists():count.unlink();removed.append('handwriting/lm_csr.bin')
    budget=sum((assets/name).stat().st_size for name in ('language_model/model.cng',
        'language_model/handwriting_fusion.json','quick/quick3.bin','quick/quick5.bin','handwriting/labels.txt'))
    if budget>=30_000_000:raise ValueError('Prediction resource budget exceeded')
    return dict(shared_model_staged=True,model_sha256=sha(target),prediction_resource_bytes=budget,
                removed=removed,backup=str(backup),experimental=True,production_approved=False)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--project',type=Path,default=Path(__file__).resolve().parents[1])
    p.add_argument('--model-source',type=Path,required=True)
    p.add_argument('--native-verification',type=Path,required=True)
    p.add_argument('--backup-root',type=Path,required=True)
    p.add_argument('--fusion-json',type=Path,default=None)
    a=p.parse_args();print(json.dumps(stage(a.project,a.model_source,a.native_verification,a.backup_root,a.fusion_json),indent=2))
