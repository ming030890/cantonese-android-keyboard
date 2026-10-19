"""Stage one verified experimental CNN and archive/remove obsolete LM assets."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import struct


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def stage(project,source,verification,backup):
    project,source,backup=map(Path,(project,source,backup))
    proof=json.loads(Path(verification).read_text())
    if not proof['passed'] or proof['model_sha256']!=sha(source):
        raise ValueError('Actual native verification required before replacing assets')
    raw=source.read_bytes()
    if raw[:8]!=b'KBCNN01\0':raise ValueError('Expected shared V2 CNN')
    size=struct.unpack('<I',raw[8:12])[0];meta=json.loads(raw[12:12+size])
    assets=project/'app/src/main/assets'
    # Preserve the exact previous local assets before cleanup; never overwrite it.
    if not backup.exists():
        backup.mkdir(parents=True)
        for directory in ('typing','english','language_model'):
            if (assets/directory).exists():shutil.copytree(assets/directory,backup/directory)
        for file in ('selected_model.json',):
            shutil.copy2(project/'config/language_model'/file,backup/file)
        count=assets/'handwriting/lm_csr.bin'
        if count.exists():shutil.copy2(count,backup/'lm_csr.bin')
    target=assets/'language_model/model.cng'
    target.parent.mkdir(parents=True,exist_ok=True)
    tmp=target.with_suffix('.tmp');shutil.copy2(source,tmp);tmp.replace(target)
    fusion=dict(version=1,weights=dict(unigram=0,bigram=0,neural=1.),
        neural_model_sha256=sha(source),calibrated=False,internal_trial=True,
        selection='Explicit initial fusion weight for shared-CNN integration; not a fitted calibration',
        inputs=dict(handwriting_model_sha256=sha(assets/'handwriting/model.safetensors'),
                    labels_sha256=sha(assets/'handwriting/labels.txt'),counts_sha256=hashlib.sha256(b'').hexdigest()))
    fusion_source=project/'config/language_model/handwriting_fusion_cnn_v2.json'
    fusion_source.write_text(json.dumps(fusion,indent=2)+'\n')
    shutil.copy2(fusion_source,assets/'language_model/handwriting_fusion.json')
    config={k:meta[k] for k in ('vocab_size','embed_dim','hidden_dim','ffn_dim','dilations','context_tokens','right_context_tokens')}
    selection=dict(format='KBCNN_INT8_V1',model_source=str(source),model_sha256=sha(source),
        model_bytes=target.stat().st_size,config=config,
        fusion_source=str(fusion_source.relative_to(project)),fusion_sha256=sha(fusion_source),
        neural_short_contexts=True,count_table_packaged=False,experimental=True,production_approved=False,
        selection='User requested one shared Cantonese/English/handwriting LM; English is explicit prefix completion only',
        qualification='V2 improves contextual accuracy but fails English-prefix and mixed quantization retention guards',
        native_verification=str(verification),old_assets_backup=str(backup))
    (project/'config/language_model/selected_model.json').write_text(json.dumps(selection,indent=2)+'\n')
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
    a=p.parse_args();print(json.dumps(stage(a.project,a.model_source,a.native_verification,a.backup_root),indent=2))
