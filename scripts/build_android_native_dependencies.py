#!/usr/bin/env python3
"""Restore pinned Android math libraries in a persistent, checksum-verified cache."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

PROJECT = Path(__file__).resolve().parents[1]


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1 << 20), b''):
            digest.update(block)
    return digest.hexdigest()


def output(args, **kwargs):
    return subprocess.check_output([str(a) for a in args], text=True, **kwargs).strip()


def checkout(source, spec):
    if not (source / '.git').is_dir():
        if source.exists() and any(source.iterdir()):
            raise ValueError(f'Refusing to replace a non-checkout directory: {source}')
        source.mkdir(parents=True, exist_ok=True)
        subprocess.run(['git', '-C', str(source), 'init', '-q'], check=True)
    revision = spec['revision']
    if subprocess.run(['git', '-C', str(source), 'cat-file', '-e', revision + '^{commit}'],
                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
        subprocess.run(['git', '-C', str(source), 'fetch', '--depth=1', spec['repository'], revision], check=True)
    if output(['git', '-C', source, 'status', '--porcelain', '--untracked-files=no']):
        raise ValueError(f'Native source checkout has modified tracked files: {source}')
    subprocess.run(['git', '-C', str(source), 'checkout', '-q', '--detach', revision], check=True)
    if output(['git', '-C', source, 'rev-parse', 'HEAD']) != revision:
        raise ValueError(f'Native source revision mismatch: {source}')


def logged(args, log, **kwargs):
    with log.open('w') as stream:
        result = subprocess.run([str(a) for a in args], stdout=stream, stderr=subprocess.STDOUT, **kwargs)
    if result.returncode:
        tail = '\n'.join(log.read_text(errors='replace').splitlines()[-25:])
        raise RuntimeError(f'Native build failed; see {log}\n{tail}')


def verify_archive(path, required, tools):
    headers = output([tools / 'llvm-readelf', '-h', path])
    machines = [line.split(':', 1)[1].strip() for line in headers.splitlines() if 'Machine:' in line]
    if not machines or set(machines) != {'AArch64'}:
        raise ValueError(f'Expected only AArch64 ELF objects in {path}: {set(machines)}')
    symbols = output([tools / 'llvm-nm', '--defined-only', '--extern-only', path])
    names = {line.split()[-1] for line in symbols.splitlines() if line.split()}
    if not set(required) <= names:
        raise ValueError(f'Missing required native symbols in {path}: {set(required) - names}')


def build(check_only=False, jobs=4):
    spec = json.loads((PROJECT / 'config/android_native_dependencies.json').read_text())
    sdk = Path(os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'Library/Android/sdk')))
    ndk = Path(os.environ.get('ANDROID_NDK_HOME', str(sdk / 'ndk' / spec['ndk_version'])))
    host = 'darwin-x86_64' if sys.platform == 'darwin' else 'linux-x86_64'
    tools = ndk / 'toolchains/llvm/prebuilt' / host / 'bin'
    compiler = tools / f'aarch64-linux-android{spec["android_api"]}-clang'
    for tool in (compiler, tools / 'llvm-ar', tools / 'llvm-ranlib', tools / 'llvm-nm', tools / 'llvm-readelf'):
        if not tool.is_file():
            raise ValueError(f'Required NDK tool missing: {tool}')
    cache = Path(os.environ.get('NATIVE_DEPS_DIR', str(Path.home() / 'Library/Caches/cantonese-voice-keyboard/native')))
    identity = dict(spec=spec, ndk=str(ndk.resolve()), ndk_properties=sha(ndk / 'source.properties'),
                    compiler=output([compiler, '--version']).splitlines()[0])
    key = hashlib.sha256(json.dumps(identity, sort_keys=True).encode()).hexdigest()[:16]
    build_dir = cache / 'builds' / key
    manifest = build_dir / 'manifest.json'
    if manifest.is_file():
        stored = json.loads(manifest.read_text())
        if stored['identity'] == identity and all(
            Path(lib['path']).is_file() and sha(Path(lib['path'])) == lib['sha256']
            for lib in stored['libraries'].values()
        ):
            return stored | {'manifest': str(manifest)}
    if check_only:
        raise ValueError('Pinned native dependencies are missing or changed. Run scripts/build_android_native_dependencies.py')
    build_dir.mkdir(parents=True, exist_ok=True)
    sources = {name: cache / 'sources' / f'{name}-{spec[name]["revision"][:8]}' for name in ('openblas', 'kleidiai')}
    for name, source in sources.items():
        print(f'Preparing pinned {name} source...', file=sys.stderr, flush=True)
        checkout(source, spec[name])
    env = os.environ.copy()
    for name in ('CC', 'CXX', 'AR', 'RANLIB', 'CFLAGS', 'CXXFLAGS', 'LDFLAGS'):
        env.pop(name, None)
    env['PATH'] = str(tools) + os.pathsep + env.get('PATH', '')
    print(f'Building OpenBLAS 0.3.34 (ARMV8, static, single-thread); log: {build_dir / "openblas.log"}', file=sys.stderr, flush=True)
    logged(['make', 'clean'], build_dir / 'openblas-clean.log', cwd=sources['openblas'], env=env)
    logged(['make', f'-j{jobs}', 'TARGET=ARMV8', 'OSNAME=Android', 'BINARY=64',
            f'CC={compiler}', 'HOSTCC=/usr/bin/clang', f'AR={tools / "llvm-ar"}',
            f'RANLIB={tools / "llvm-ranlib"}', 'NOFORTRAN=1', 'ONLY_CBLAS=1',
            'USE_THREAD=0', 'NUM_THREADS=1', 'NO_SHARED=1'],
           build_dir / 'openblas.log', cwd=sources['openblas'], env=env)
    blas_dir = build_dir / 'openblas'
    blas_dir.mkdir(exist_ok=True)
    blas = blas_dir / 'libopenblas.a'
    shutil.copy2(sources['openblas'] / 'libopenblas.a', blas)
    print(f'Building KleidiAI at {spec["kleidiai"]["revision"][:12]}...', file=sys.stderr, flush=True)
    kai_dir = build_dir / 'kleidiai'
    logged(['cmake', '-S', sources['kleidiai'], '-B', kai_dir, '-G', 'Unix Makefiles',
            f'-DCMAKE_TOOLCHAIN_FILE={ndk / "build/cmake/android.toolchain.cmake"}',
            '-DCMAKE_BUILD_TYPE=Release', '-DCMAKE_POSITION_INDEPENDENT_CODE=ON',
            '-DANDROID_ABI=arm64-v8a', f'-DANDROID_PLATFORM=android-{spec["android_api"]}',
            '-DBUILD_SHARED_LIBS=OFF', '-DKLEIDIAI_BUILD_TESTS=OFF', '-DKLEIDIAI_BUILD_BENCHMARK=OFF'],
           build_dir / 'kleidiai-configure.log', env=env)
    logged(['cmake', '--build', kai_dir, '--target', 'kleidiai', '--parallel', jobs],
           build_dir / 'kleidiai.log', env=env)
    kai = kai_dir / 'libkleidiai.a'
    verify_archive(blas, ['cblas_sgemm'], tools)
    verify_archive(kai, ['kai_run_matmul_clamp_f32_qai8dxp4x8_qsi8cxp4x8_16x4_neon_i8mm',
                         'kai_run_matmul_clamp_f32_qai8dxp1x8_qsi8cxp4x8_1x4_neon_dotprod'], tools)
    result = dict(identity=identity, compiler=str(compiler), ndk_tools=str(tools),
                  libraries={name: dict(path=str(path), sha256=sha(path)) for name, path in [('openblas', blas), ('kleidiai', kai)]})
    temporary = manifest.with_suffix('.tmp')
    temporary.write_text(json.dumps(result, indent=2) + '\n')
    temporary.replace(manifest)
    return result | {'manifest': str(manifest)}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Verify the cache without downloading or building')
    parser.add_argument('--jobs', type=int, default=4)
    args = parser.parse_args()
    if args.jobs < 1:
        parser.error('--jobs must be positive')
    try:
        print(json.dumps(build(args.check, args.jobs), indent=2))
    except (ValueError, OSError, RuntimeError, subprocess.CalledProcessError) as error:
        parser.exit(1, f'{error}\n')
