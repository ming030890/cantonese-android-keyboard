#!/usr/bin/env python3
"""Replay a WAV through the debug app's real IME, with a process restart by default."""
import argparse
import hashlib
import json
import re
from pathlib import Path
import subprocess
import time
import wave

NAMESPACE = 'com.madeinhk.cantonesevoicekeyboard'

def query_device_context(command):
    ctx = {}
    try:
        battery = command('shell', 'dumpsys', 'battery', capture_output=True, text=True).stdout
        temp_match = re.search(r'temperature:\s*(\d+)', battery)
        level_match = re.search(r'level:\s*(\d+)', battery)
        if temp_match:
            ctx['battery_temp_c'] = int(temp_match.group(1)) / 10.0
        if level_match:
            ctx['battery_pct'] = int(level_match.group(1))
    except Exception:
        pass
    try:
        thermal = command('shell', 'dumpsys', 'thermalservice', capture_output=True, text=True).stdout
        status_match = re.search(r'Thermal Status:\s*(\d+)', thermal)
        if status_match:
            ctx['thermal_status'] = int(status_match.group(1))
    except Exception:
        pass
    return ctx

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('wav', type=Path, help='16kHz mono PCM16 WAV')
    parser.add_argument('--package', help='installed debug package; automatically detects normal or side-by-side debug app')
    parser.add_argument('--serial', help='adb device serial')
    parser.add_argument('--runs', type=int, default=2, help='first query plus warm repeats (1–10)')
    parser.add_argument('--restart-delays', type=str, default=None, help='comma-separated restart delays in ms, e.g. "0,50,200"')
    parser.add_argument('--keep-running', action='store_true', help='skip process force-stop')
    parser.add_argument('--output', type=Path, default=Path('build/voice-benchmark'))
    args = parser.parse_args()
    adb = ['adb'] + (['-s', args.serial] if args.serial else [])
    def command(*parts, **kwargs):
        return subprocess.run(adb + list(parts), check=True, **kwargs)
    package = args.package
    if package is None:
        for candidate in (NAMESPACE, NAMESPACE + '.voicebenchmark'):
            probe = subprocess.run(adb + ['shell', 'run-as', candidate, 'true'], capture_output=True)
            if probe.returncode == 0:
                package = candidate
                break
        if package is None:
            parser.error('No debuggable app installed; deploy a debug build first')
    if not re.fullmatch(r'[A-Za-z0-9_.]+', package):
        parser.error('Invalid Android package name')
    with wave.open(str(args.wav), 'rb') as wav:
        if (wav.getframerate(), wav.getnchannels(), wav.getsampwidth(), wav.getcomptype()) != (16000, 1, 2, 'NONE'):
            parser.error('WAV must be uncompressed 16kHz mono PCM16')
        pcm = wav.readframes(wav.getnframes())
    if not pcm:
        parser.error('WAV contains no audio')
    args.output.mkdir(parents=True, exist_ok=True)
    selected = command('shell', 'settings', 'get', 'secure', 'default_input_method', capture_output=True, text=True).stdout.strip()
    target_ime = package + '/' + NAMESPACE + '.CantoneseInputMethodService'
    previous_trace = command('shell', 'getprop', 'debug.qwen_asr.latency', capture_output=True, text=True).stdout.strip()
    metadata = {
        'package': package, 'wav': str(args.wav.resolve()),
        'wav_sha256': hashlib.sha256(args.wav.read_bytes()).hexdigest(),
        'process_restarted': not args.keep_running,
        'audio_ms': len(pcm) / 32,
        'bounded_prefetch_override': command('shell', 'getprop', 'debug.qwen_asr.bounded_prefetch', capture_output=True, text=True).stdout.strip(),
        'matvec_override': command('shell', 'getprop', 'debug.qwen_asr.matmul_m1', capture_output=True, text=True).stdout.strip(),
        'defer_stop_override': command('shell', 'getprop', 'debug.qwen_asr.defer_stop', capture_output=True, text=True).stdout.strip(),
        'threads_override': command('shell', 'getprop', 'debug.qwen_asr.threads', capture_output=True, text=True).stdout.strip(),
        'worker_pin_override': command('shell', 'getprop', 'debug.qwen_asr.worker_pin', capture_output=True, text=True).stdout.strip(),
        'chunk_ms_override': command('shell', 'getprop', 'debug.qwen_asr.chunk_ms', capture_output=True, text=True).stdout.strip(),
        'backlog_ms_override': command('shell', 'getprop', 'debug.qwen_asr.backlog_ms', capture_output=True, text=True).stdout.strip(),
        'device': command('shell', 'getprop', 'ro.product.model', capture_output=True, text=True).stdout.strip(),
        'local_native_sha256': hashlib.sha256((Path(__file__).resolve().parents[1] / 'app/src/main/jniLibs/arm64-v8a/libqwen_asr.so').read_bytes()).hexdigest(),
        'device_context': query_device_context(command),
    }
    stripped = Path(__file__).resolve().parents[1] / 'app/build/intermediates/stripped_native_libs/debug/stripDebugDebugSymbols/out/lib/arm64-v8a/libqwen_asr.so'
    if stripped.exists():
        metadata['expected_packaged_native_sha256'] = hashlib.sha256(stripped.read_bytes()).hexdigest()
    (args.output / 'metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
    since = command('shell', 'date', r'+%m-%d\ %H:%M:%S.000', capture_output=True, text=True).stdout.strip()
    log_stream = (args.output / 'logcat.txt').open('w')
    logger = subprocess.Popen(adb + ['logcat', '-v', 'threadtime', '-T', since, 'VoiceLatency:I', 'VoiceNative:I', 'QAsrEngine:I', 'CantoneseIME:I', 'ModelAssetPack:I', '*:S'], stdout=log_stream, stderr=subprocess.PIPE)
    try:
        if not args.keep_running:
            command('shell', 'am', 'force-stop', package, stdout=subprocess.DEVNULL)
        command('shell', 'ime', 'enable', target_ime, stdout=subprocess.DEVNULL)
        command('shell', 'ime', 'set', target_ime, stdout=subprocess.DEVNULL)
        command('shell', 'setprop', 'debug.qwen_asr.latency', '1')
        command('shell', 'run-as', package, 'mkdir', '-p', 'files/voice-benchmark')
        command('shell', 'run-as', package, 'rm', '-f', 'files/voice-benchmark/result.json')
        command('shell', 'run-as', package, 'sh', '-c', '"cat > files/voice-benchmark/input.pcm"', input=pcm)
        command('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP', stdout=subprocess.DEVNULL)

        start_cmd = ['shell', 'am', 'start', '-W', '-n', package + '/' + NAMESPACE + '.VoiceBenchmarkActivity']
        if args.restart_delays is not None:
            start_cmd += ['--es', 'restart_delays', args.restart_delays]
            expected_queries = len(args.restart_delays.split(',')) * 2
        else:
            start_cmd += ['--ei', 'runs', str(args.runs)]
            expected_queries = args.runs
        command(*start_cmd, stdout=subprocess.DEVNULL)

        deadline = time.monotonic() + 240 * min(max(expected_queries, 1), 20)
        while time.monotonic() < deadline:
            result = subprocess.run(adb + ['shell', 'run-as', package, 'cat', 'files/voice-benchmark/result.json'], capture_output=True, text=True)
            if result.returncode == 0:
                try:
                    report = json.loads(result.stdout)
                    break
                except json.JSONDecodeError:
                    pass
            time.sleep(1)
        else:
            raise RuntimeError('Benchmark timed out; inspect VoiceLatency logcat')
    finally:
        logger.terminate()
        logger.wait(timeout=10)
        log_stream.close()
        try:
            # Close the debug editor and release its keep-screen-on flag after a cold test.
            if not args.keep_running:
                command('shell', 'am', 'force-stop', package, stdout=subprocess.DEVNULL)
        finally:
            try:
                command('shell', 'ime', 'set', selected, stdout=subprocess.DEVNULL)
            finally:
                command('shell', 'setprop', 'debug.qwen_asr.latency', previous_trace or '0')

    metadata['device_context_after'] = query_device_context(command)
    (args.output / 'metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')

    if report.get('status') == 'ok':
        if 'restart_trials' in report:
            trials = report['restart_trials']
            # A superseded recording deliberately emits no global Idle while the
            # next one is recording. Its completion is native_stop_return; do
            # not turn the missing Idle milestone into a negative latency.
            for trial in trials:
                for key in ('query_1', 'query_2'):
                    if 'idle' not in trial[key]:
                        trial[key]['stop_to_idle_ms'] = None
                trial['query1_stop_to_idle_ms'] = trial['query_1'].get('stop_to_idle_ms')
            report['editor_complete'] = all(
                t.get('text', '').strip() != '' and
                ('hypothesis_callback' not in t['query_1'] or t['query_1'].get('editor_updates', 0) > 0) and
                ('hypothesis_callback' not in t['query_2'] or t['query_2'].get('editor_updates', 0) > 0)
                for t in trials
            )
            report['audio_complete'] = all(
                abs(t['query_1'].get('audio_ms', -1) - metadata['audio_ms']) < 0.01 and t['query_1'].get('rejected_chunks') == 0 and
                abs(t['query_2'].get('audio_ms', -1) - metadata['audio_ms']) < 0.01 and t['query_2'].get('rejected_chunks') == 0
                for t in trials
            )
        elif 'runs' in report:
            report['editor_complete'] = all('hypothesis_callback' not in run or run.get('editor_updates', 0) > 0 for run in report['runs'])
            report['audio_complete'] = all(abs(run.get('audio_ms', -1) - metadata['audio_ms']) < 0.01 and run.get('rejected_chunks') == 0 for run in report['runs'])
    if report.get('status') == 'ok' and 'expected_packaged_native_sha256' in metadata:
        report['native_matches_local_build'] = report.get('packaged_native_sha256') == metadata['expected_packaged_native_sha256']

    sessions = []
    current_session = None
    # Logcat may truncate a long diagnostic line within a UTF-8 code point.
    # Ground-truth editor text comes from the separate, complete result JSON.
    for line in (args.output / 'logcat.txt').read_text(errors='replace').splitlines():
        if 'VoiceNative' not in line:
            continue
        fields = dict(re.findall(r'(\w+)=([^\s]+)', line))
        ev = fields.get('event')
        if ev == 'session_created':
            current_session = {
                'session_id': int(fields.get('session_id', len(sessions) + 1)),
                'mel_ms': 0.0, 'encoder_ms': 0.0, 'prefill_ms': 0.0,
                'decode_ms': 0.0, 'process_ms': 0.0, 'max_queue_wait_ms': 0.0,
                'passes': []
            }
            sessions.append(current_session)
        elif ev == 'worker_ready' and not sessions:
            current_session = {
                'session_id': int(fields.get('session_id', 1)),
                'mel_ms': 0.0, 'encoder_ms': 0.0, 'prefill_ms': 0.0,
                'decode_ms': 0.0, 'process_ms': 0.0, 'max_queue_wait_ms': 0.0,
                'passes': []
            }
            sessions.append(current_session)

        if not sessions:
            continue
        session = sessions[-1]

        if ev == 'worker_ready':
            session['worker_ready_wall_ms'] = float(fields.get('wall_ms', 0))
        elif ev == 'decoder_preload':
            session['decoder_preload_ms'] = float(fields.get('ms', 0))
        elif ev == 'decode_pass':
            session.setdefault('decode_passes', []).append({
                key: fields.get(key) for key in ('final', 'generated', 'limit', 'prefix_tokens',
                                                'new_audio_ms', 'reused_prefill', 'prefill_rows',
                                                'prefill_ms', 'decode_ms', 'token_ready_ms')
            })
        elif ev == 'kernel':
            kernels = session.setdefault('kernels_ms', {})
            kernels[fields['name']] = kernels.get(fields['name'], 0.0) + float(fields['ms'])
        elif ev == 'thread_work':
            for key in ('cpu_ms', 'minor_faults', 'major_faults'):
                session[key] = session.get(key, 0.0) + float(fields.get(key, 0))
        elif ev in ('process', 'finish'):
            for key in ('mel_ms', 'encoder_ms', 'prefill_ms', 'decode_ms'):
                session[key] += float(fields.get(key, 0))
            session['process_ms'] += float(fields.get('process_ms', fields.get('finalize_ms', 0)))
            session['max_queue_wait_ms'] = max(session['max_queue_wait_ms'],
                float(fields.get('queue_wait_ms', fields.get('max_queue_wait_ms', 0))))
            session['passes'].append({
                'pass_id': int(fields.get('pass_id', len(session['passes']) + 1)),
                'audio_start_ms': float(fields.get('audio_start_ms', 0.0)),
                'audio_end_ms': float(fields.get('audio_end_ms', 0.0)),
                'process_ms': float(fields.get('process_ms', fields.get('finalize_ms', 0.0))),
                'is_finish': (ev == 'finish'),
            })
        if ev == 'preview_end':
            session['preview_end_wall_ms'] = float(fields.get('wall_ms', 0))
        elif ev == 'queued_audio_drained':
            session['queued_audio_drained_wall_ms'] = float(fields.get('wall_ms', 0))
        elif ev == 'native_stop_received':
            session['native_stop_received_wall_ms'] = float(fields.get('wall_ms', 0))
        elif ev == 'final_inference_start':
            session['final_inference_start_wall_ms'] = float(fields.get('wall_ms', 0))
        elif ev == 'final_inference_end':
            session['final_inference_end_wall_ms'] = float(fields.get('wall_ms', 0))
        elif ev == 'finish':
            session['finish_seen'] = True
            session['finalize_ms'] = float(fields.get('finalize_ms', 0))
        elif ev == 'native_worker_joined':
            session['native_worker_joined_seen'] = True
        elif ev == 'speech_start' and 'speech_start_audio_ms' not in session:
            session['speech_start_audio_ms'] = float(fields['audio_ms'])
        elif ev == 'endpoint':
            session['speech_end_audio_ms'] = float(fields['speech_end_audio_ms'])

    report['native_trace_complete'] = (
        len(sessions) == expected_queries and all(s.get('finish_seen') for s in sessions)
    )

    if report['native_trace_complete']:
        if 'restart_trials' in report:
            for idx, trial in enumerate(report['restart_trials']):
                trial['query_1']['native'] = sessions[idx * 2]
                trial['query_2']['native'] = sessions[idx * 2 + 1]
        elif 'runs' in report:
            for run, native in zip(report['runs'], sessions):
                run['native'] = native
                if 'editor_text' in run and 'speech_start_audio_ms' in native:
                    run['speech_onset_to_first_editor_ms'] = run['editor_text'] - native['speech_start_audio_ms']

    (args.output / 'result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if (
        report['status'] != 'ok' or
        report.get('native_matches_local_build') is False or
        report.get('audio_complete') is False or
        report.get('editor_complete') is False or
        report.get('native_trace_complete') is False
    ):
        raise SystemExit(1)

if __name__ == '__main__':
    main()
