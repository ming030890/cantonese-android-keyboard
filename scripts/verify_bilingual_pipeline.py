"""Build JNI and run assets -> bilingual inference -> keys -> final text.

Default acceptance mode exits2 if reported correction regressions remain.
Diagnostic mode collects the same failures without claiming acceptance. No
training, model promotion, device installation or publication is performed.
"""
import argparse,hashlib,json,platform,shutil,subprocess,sys
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
ENGINE=ROOT.parent/'QwenASR/crates/cantonese-ngram'
CLASS='com.madeinhk.cantonesevoicekeyboard.BilingualPipelineE2eTest'

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()

def resources(assets):
    files=[]
    for name in ('english','quick','typing','language_model'):
        files.extend(p for p in (assets/name).rglob('*') if p.is_file())
    files.extend(p for p in [assets/'handwriting/labels.txt',assets/'handwriting/lm_csr.bin'] if p.is_file())
    return {str(p.relative_to(assets)):dict(bytes=p.stat().st_size,sha256=sha(p)) for p in sorted(files)}

def check_report(root):
    totals={k:int(root.get(k,0)) for k in ('tests','failures','errors','skipped')}
    rows=[]
    for output in root.findall('system-out'):
        for line in (output.text or '').splitlines():
            if line.startswith('BILINGUAL_E2E '):rows.append(json.loads(line[len('BILINGUAL_E2E '):]))
    core=[r for r in rows if not r['quality_regression']];quality=[r for r in rows if r['quality_regression']]
    # Missing rows, skipped native tests and partial suites are never a pass.
    expected_core={f'english/{mode}/rapid={rapid}/touch={touch}/{word}' for mode in ('AlphaNum','Quick')
        for rapid in ('false','true') for touch in ('false','true') for word in ('teh','helllo','wprld','typo','Xentara')}
    expected_core.update(('cantonese/traditional','cantonese/whole-phrase','mixed/cantonese-english'))
    mechanics=(totals['tests']==4 and all(totals[k]==0 for k in ('failures','errors','skipped')) and len(core)==len(expected_core)
        and {r['name'] for r in core}==expected_core and all(r['passed'] and r['actual']==r['expected'] for r in core))
    quality_passed=(len(quality)==14 and len({r['name'] for r in quality})==14
        and all(sum(r['name'].startswith('reported/'+mode+'/') for r in quality)==7 for mode in ('AlphaNum','Quick'))
        and all(r['passed'] and r['actual']==r['expected'] for r in quality))
    return dict(junit=totals,mechanisms_passed=mechanics,reported_corrections_passed=quality_passed,
        core_cases=core,reported_corrections=quality)

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--out',type=Path,required=True)
    p.add_argument('--mode',choices=['acceptance','diagnostic'],default='acceptance')
    p.add_argument('--assets',type=Path,default=ROOT/'app/src/main/assets')
    a=p.parse_args();a.out=a.out.resolve();a.assets=a.assets.resolve()
    a.out.mkdir(parents=True,exist_ok=False)
    implementation=[ROOT/'app/build.gradle.kts',ROOT/'app/src/test/java/com/madeinhk/cantonesevoicekeyboard/BilingualPipelineE2eTest.kt',
        ROOT/'app/src/scenarioTest/java/com/madeinhk/cantonesevoicekeyboard/KeyboardScenario.kt']
    implementation+=list((ROOT/'app/src/main/java/com/madeinhk/cantonesevoicekeyboard/english').glob('*.kt'))
    implementation+=list((ROOT/'app/src/main/java/com/madeinhk/cantonesevoicekeyboard/quick').glob('*.kt'))
    implementation+=list((ROOT/'app/src/main/java/com/madeinhk/cantonesevoicekeyboard').glob('KeyboardSession*.kt'))
    before=resources(a.assets);report=dict(scope='Host JNI + production loaders/key receiver/controller; scenario editor/OS ports. Not a phone latency or IME-service qualification.',
        mode=a.mode,production_approved=False,internal_typing_trial=True,assets=str(a.assets),files=before,resource_bytes=sum(x['bytes'] for x in before.values()),
        implementation={str(p.relative_to(ROOT)):sha(p) for p in sorted(implementation)},
        commands=[],script_sha256=sha(Path(__file__)),mechanisms_passed=False,reported_corrections_passed=False)
    report['resource_budget_passed']=report['resource_bytes']<30_000_000
    def save(): (a.out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    xml=ROOT/'app/build/test-results/testDebugUnitTest'/('TEST-'+CLASS+'.xml')
    previous_xml=(xml.stat().st_mtime_ns,sha(xml)) if xml.exists() else None
    def capture_junit():
        shutil.copy2(xml,a.out/'junit.xml');report.update(check_report(ET.parse(xml).getroot()))
    def run(command,name,cwd):
        report['commands'].append(command);save()
        with (a.out/(name+'.log')).open('w') as stream:
            result=subprocess.run(command,cwd=cwd,stdout=stream,stderr=subprocess.STDOUT)
        if result.returncode:raise RuntimeError(name+' failed; see '+str(a.out/(name+'.log')))
    try:
        run(['cargo','build','--manifest-path',str(ENGINE/'Cargo.toml'),'--release','--lib','--features','android'],'native-build',ENGINE)
        native=ENGINE/'target/release'/('libcantonese_engine.dylib' if platform.system()=='Darwin' else 'libcantonese_engine.so')
        report['native']=dict(path=str(native),sha256=sha(native))
        run(['./gradlew',':app:testDebugUnitTest','--rerun-tasks','--tests',CLASS,'-PenglishNativeLibrary='+str(native),
            '-PbilingualAssets='+str(a.assets),'-PinternalTypingTrial=true','--console=plain'],'e2e',ROOT)
        capture_junit()
        report['assets_unchanged']=before==resources(a.assets)
        report['accepted']=all(report[k] for k in ('resource_budget_passed','mechanisms_passed','reported_corrections_passed','assets_unchanged'))
        save();print(json.dumps({k:v for k,v in report.items() if k in ('accepted','resource_bytes','resource_budget_passed','mechanisms_passed','reported_corrections_passed','assets_unchanged')},indent=2))
        if not report['mechanisms_passed'] or not report['resource_budget_passed'] or not report['assets_unchanged']:return 1
        return 0 if a.mode=='diagnostic' or report['accepted'] else 2
    except Exception as error:
        # A failing behavior test still contains useful final editor output.
        # Never substitute an old XML file after a compilation/build failure.
        if xml.exists() and (xml.stat().st_mtime_ns,sha(xml))!=previous_xml:
            capture_junit()
        report.update(error=str(error),accepted=False,assets_unchanged=before==resources(a.assets))
        save();print(str(error),file=sys.stderr);return 1

if __name__=='__main__':raise SystemExit(main())
