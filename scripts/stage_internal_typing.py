"""Stage a hash-bound internal profile and complete model/vocabulary bundle.
Use an isolated output for E2E checks before changing app assets.
"""
import argparse,hashlib,json,shutil
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()

def main():
    p=argparse.ArgumentParser();p.add_argument('--profile',type=Path,default=ROOT/'config/typing_internal')
    p.add_argument('--output-assets',type=Path,default=ROOT/'app/src/main/assets');a=p.parse_args()
    source=a.profile.resolve();assets=a.output_assets.resolve();base=ROOT/'app/src/main/assets'
    selection=source/'profile.json'
    selected=json.loads(selection.read_text()) if selection.exists() else dict(model='/Volumes/SSD/cantonese_ngram/experiments/compact-warmstart-v22/model.cng')
    model=Path(selected['model']);dictionary=Path(selected.get('dictionary',str(base/'english/en-v2.bin')))
    validity=Path(selected['validity']) if selected.get('validity') else None
    inputs=dict(model=model,dictionary=dictionary)
    if validity:inputs['validity']=validity
    if selected.get('vocabulary_metadata'):inputs['vocabulary_metadata']=Path(selected['vocabulary_metadata'])
    for key,path in inputs.items():
        if key+'_sha256' in selected:assert sha(path)==selected[key+'_sha256'],('Changed selected resource',key)
    policies={name:json.loads((source/(name+'.json')).read_text()) for name in ('boundary','context_revision')}
    for name,value in policies.items():
        assert value['internal_trial'] and not value['heldout_approved'] and not value['production_approved']
        assert value['correction_scope']==name and value['model_sha256']==sha(model) and value['dictionary_sha256']==sha(dictionary)
        if validity:assert value['validity_sha256']==sha(validity)
    if assets!=base:
        assert not assets.exists(),'Use a fresh isolated directory'
        for name in ('english','quick','typing','language_model'):shutil.copytree(base/name,assets/name)
        for name in ('labels.txt','lm_csr.bin'):
            path=base/'handwriting'/name
            if path.exists():
                target=assets/'handwriting'/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(path,target)
    replacements={assets/'typing/model.cng':model,assets/'english/en-v2.bin':dictionary,
        assets/'typing/calibration.json':source/'boundary.json',assets/'typing/revision_calibration.json':source/'context_revision.json'}
    if validity:replacements[assets/'english/validity/valid_words.txt']=validity
    if 'vocabulary_metadata' in inputs:replacements[assets/'english/validity/compiled_vocabulary.json']=inputs['vocabulary_metadata']
    files=set(replacements)
    for name in ('english','quick','typing','language_model'):
        files.update(path for path in (assets/name).rglob('*') if path.is_file() and path!=assets/'typing/manifest.json')
    files.update(path for path in [assets/'handwriting/labels.txt',assets/'handwriting/lm_csr.bin'] if path.is_file())
    size=sum(replacements.get(path,path).stat().st_size for path in files)
    manifest=dict(sha256=sha(model),dictionary_sha256=sha(dictionary),trial_only=True,production_approved=False,
        profile=selected.get('name','legacy-v53'),files={str(path.relative_to(assets)):sha(replacements.get(path,path)) for path in sorted(files)})
    for _ in range(8):manifest['resource_bytes']=size+len((json.dumps(manifest,indent=2)+'\n').encode())
    data=(json.dumps(manifest,indent=2)+'\n').encode();total=size+len(data)
    assert total<30_000_000,('LM/vocabulary/candidate resource budget',total)
    for target,path in replacements.items():
        target.parent.mkdir(parents=True,exist_ok=True)
        if target.resolve()!=path.resolve():shutil.copy2(path,target)
    (assets/'typing/manifest.json').write_bytes(data)
    print(json.dumps(dict(internal_typing_trial_staged=True,assets=str(assets),resource_bytes=total,model_sha256=sha(model))))

if __name__=='__main__':main()
