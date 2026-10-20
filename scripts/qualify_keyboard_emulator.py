"""Archive isolated Android functional smoke checks; never release approval."""
import argparse,hashlib,json,os,shutil,subprocess
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
CLASSES=['com.madeinhk.cantonesevoicekeyboard.SharedLmEnglishInstrumentedTest',
 'com.madeinhk.cantonesevoicekeyboard.ui.keyboard.KeyboardTouchControllerTest']

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    p=argparse.ArgumentParser();p.add_argument('--out',type=Path,required=True);p.add_argument('--serial',default='emulator-5554');a=p.parse_args()
    if not a.serial.startswith('emulator-'):raise ValueError('This smoke runner only installs onto an emulator')
    adb=Path('/Users/tony/Library/Android/sdk/platform-tools/adb')
    devices=subprocess.check_output([str(adb),'devices'],text=True)
    active=[s.split()[0] for s in devices.splitlines()[1:] if s.strip()]
    if active!=[a.serial]:raise ValueError('Require only the requested emulator connected; do not install onto another device')
    a.out.mkdir(parents=True,exist_ok=True);reports=[]
    for name in CLASSES:
        dest=a.out/name.rsplit('.',1)[-1];dest.mkdir(exist_ok=True)
        command=['./gradlew',':app:connectedDebugAndroidTest','-Pandroid.testInstrumentationRunnerArguments.class='+name,'--console=plain']
        with (dest/'gradle.log').open('w') as f:result=subprocess.run(command,cwd=ROOT,env=dict(os.environ,ANDROID_SERIAL=a.serial),stdout=f,stderr=subprocess.STDOUT)
        directory=ROOT/'app/build/outputs/androidTest-results/connected/debug'
        if directory.exists():shutil.copytree(directory,dest/'results',dirs_exist_ok=True)
        totals={'tests':0,'failures':0,'errors':0,'skipped':0}
        for path in directory.glob('TEST-*.xml'):
            root=ET.parse(path).getroot()
            for key in totals:totals[key]+=int(root.get(key,0))
        report={'class':name,'exit_code':result.returncode,**totals};reports.append(report);print(json.dumps(report),flush=True)
        if result.returncode or totals['tests']==0 or totals['failures'] or totals['errors'] or totals['skipped']:raise RuntimeError('Android smoke check did not pass; archived logs retained')
    paths=['app/src/main/jniLibs/arm64-v8a/libcantonese_engine.so','app/src/main/assets/language_model/model.cng',
        'app/src/main/assets/english/en.bin','app/src/main/assets/english/en-v2.bin',
        'app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']
    report={'serial':a.serial,'checks':reports,'files':{s:{'sha256':sha(ROOT/s),'bytes':(ROOT/s).stat().st_size} for s in paths},
        'scope':'ARM64 Android emulator: packaged JNI, real MotionEvents, isolated EditText/InputConnection with fixed correction outputs. Not real IME-service tap replay or physical phone latency.',
        'production_approved':False}
    (a.out/'report.json').write_text(json.dumps(report,indent=2)+'\n')

if __name__=='__main__':main()
