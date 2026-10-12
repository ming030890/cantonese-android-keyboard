"""Reject mismatched language-model/handwriting calibration before packaging."""
import argparse,hashlib,json,math,struct,zipfile
from pathlib import Path

def sha(path):
    h=hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(1<<20),b''):h.update(block)
    return h.hexdigest()

def verify(project):
    assets=project/'app/src/main/assets';model=assets/'language_model/model.cng'
    with model.open('rb') as stream:
        header=stream.read(8)
        if len(header)!=8 or header[:4] not in (b'CNG1',b'CNG2'):raise ValueError('Unsupported model format')
        size=struct.unpack('<I',header[4:])[0]
        if size>model.stat().st_size-8:raise ValueError('Truncated model metadata')
        metadata=json.loads(stream.read(size))
    neural_only=header[:4]==b'CNG1' and metadata.get('neural_short_contexts',False)
    calibration=json.loads((assets/'language_model/handwriting_fusion.json').read_text())
    if calibration['version']!=1:raise ValueError('Unsupported fusion calibration')
    if calibration['neural_model_sha256']!=sha(model):raise ValueError('Fusion/model hash mismatch')
    for name,file in [('handwriting_model_sha256','model.safetensors'),('labels_sha256','labels.txt')]:
        if calibration['inputs'][name]!=sha(assets/'handwriting'/file):raise ValueError('Fusion handwriting input changed: '+file)
    count_asset=assets/'handwriting/lm_csr.bin'
    if neural_only:
        if model.stat().st_size>10000000:raise ValueError('Neural model exceeds10MB')
        if count_asset.exists():raise ValueError('Neural-only package still contains count table')
        if calibration['inputs']['counts_sha256']!=hashlib.sha256(b'').hexdigest():raise ValueError('Neural-only calibration requires absent count table')
        if calibration['weights']['unigram'] or calibration['weights']['bigram']:raise ValueError('Neural-only calibration uses count weights')
    elif calibration['inputs']['counts_sha256']!=sha(count_asset):raise ValueError('Fusion count input changed')
    weights=calibration['weights']
    if set(weights)!={'unigram','bigram','neural'} or any(not math.isfinite(v) or not 0<=v<=16 for v in weights.values()):raise ValueError('Invalid fusion weights')
    result={'passed':True,'format':header[:4].decode(),'model_sha256':sha(model),'bytes':model.stat().st_size,'fusion_weights':weights}
    selection_path=project/'config/language_model/selected_model.json'
    if selection_path.exists():
        selection=json.loads(selection_path.read_text())
        if selection['model_sha256']!=result['model_sha256'] or selection['model_bytes']!=result['bytes']:raise ValueError('Packaged model differs from selected release model')
        if selection['config']!=metadata.get('config'):raise ValueError('Selected architecture mismatch')
        if selection['fusion_sha256']!=sha(assets/'language_model/handwriting_fusion.json'):raise ValueError('Selected fusion mismatch')
        if selection['neural_short_contexts']!=neural_only or selection['count_table_packaged']!=count_asset.exists():raise ValueError('Selected inference policy mismatch')
        result['selected_model_verified']=True
    if neural_only:result.update(neural_short_contexts=True,config=metadata['config'],count_table_packaged=False)
    if header[:4]==b'CNG2':
        result['memory_hidden_width']=next(t['shape'][0] for t in metadata['tensors'] if t['name']=='hidden.weight')
        result['known_contexts']=len(metadata['keys'])
        result['parent_share']=metadata['parent_share']
    return result

def verify_package(project, package):
    bundle=package.suffix=='.aab'
    if package.suffix not in {'.apk','.aab'}:raise ValueError('Expected APK or AAB')
    prefix='base/' if bundle else ''
    variant=package.parent.name if package.parent.name in {'debug','release'} else ('release' if 'release' in package.stem else 'debug')
    assets=project/'app/src/main/assets'
    files=['language_model/model.cng','language_model/handwriting_fusion.json',
           'handwriting/model.safetensors','handwriting/labels.txt',
           'quick/quick3.bin','quick/quick5.bin']
    checked={}
    def check(archive, entry, source):
        h=hashlib.sha256()
        with archive.open(entry) as stream:
            for block in iter(lambda:stream.read(1<<20),b''):h.update(block)
        actual=h.hexdigest()
        if actual!=sha(source):raise ValueError('Packaged file mismatch: '+entry)
        checked[entry]=actual
    with zipfile.ZipFile(package) as archive:
        count_asset=assets/'handwriting/lm_csr.bin'
        if count_asset.exists():files.append('handwriting/lm_csr.bin')
        elif prefix+'assets/handwriting/lm_csr.bin' in archive.namelist():raise ValueError('Obsolete count table packaged')
        for file in files:check(archive,prefix+'assets/'+file,assets/file)
        for library in ['libcantonese_engine.so', 'libqwen_asr.so']:
            stripped=project/f'app/build/intermediates/stripped_native_libs/{variant}/strip{variant.capitalize()}DebugSymbols/out/lib/arm64-v8a'/library
            if not stripped.is_file():raise ValueError('Missing native build output: '+str(stripped))
            check(archive,prefix+'lib/arm64-v8a/'+library,stripped)
        packs=[]
        if bundle:
            for letter in 'abcde':
                name='model_pack_'+letter
                entries=[n for n in archive.namelist() if n.startswith(name+'/assets/') and n.endswith('.safetensors')]
                if not entries:raise ValueError('Missing asset pack: '+name)
                for entry in entries:
                    source=project/name/'src/main'/entry.split('/',1)[1]
                    if archive.getinfo(entry).file_size!=source.stat().st_size:raise ValueError('Asset pack size mismatch: '+entry)
                packs.append(name)
    return {'passed':True,'package':str(package),'checked_sha256':checked,'asset_packs':packs}

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--project',type=Path,default=Path(__file__).resolve().parents[1]);p.add_argument('--package',type=Path,action='append',default=[]);a=p.parse_args()
    result=verify(a.project)
    if a.package:result['packages']=[verify_package(a.project,path) for path in a.package]
    print(json.dumps(result))
