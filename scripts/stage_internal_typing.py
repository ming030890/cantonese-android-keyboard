"""Stage an explicitly authorized internal trial; production approval stays false."""
import hashlib,json,shutil
from pathlib import Path
root=Path(__file__).resolve().parents[1]
source=root/'config/typing_internal'
model=Path('/Volumes/SSD/cantonese_ngram/experiments/compact-warmstart-v22/model.cng')
out=root/'app/src/main/assets/typing'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
model_hash=sha(model);dictionary_hash=sha(root/'app/src/main/assets/english/en-v2.bin')
for name in ('boundary','context_revision'):
    value=json.loads((source/(name+'.json')).read_text())
    assert value['internal_trial'] and not value['heldout_approved'] and not value['production_approved']
    assert value['model_sha256']==model_hash and value['dictionary_sha256']==dictionary_hash
resources=[model,root/'app/src/main/assets/language_model/model.cng']
resources+=list((root/'app/src/main/assets/english').glob('*.bin'))+list((root/'app/src/main/assets/quick').glob('*.bin'))
size=sum(p.stat().st_size for p in resources)
assert size<30_000_000,('LM/vocabulary/candidate resource budget',size)
out.mkdir(parents=True,exist_ok=True)
shutil.copy2(model,out/'model.cng')
shutil.copy2(source/'boundary.json',out/'calibration.json')
shutil.copy2(source/'context_revision.json',out/'revision_calibration.json')
(out/'manifest.json').write_text(json.dumps({'sha256':model_hash,'dictionary_sha256':dictionary_hash,'trial_only':True,'production_approved':False,'resource_bytes':size},indent=2)+'\n')
print(json.dumps({'internal_typing_trial_staged':True,'resource_bytes':size,'model_sha256':model_hash}))
