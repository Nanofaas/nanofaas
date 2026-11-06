#!/usr/bin/env python3
"""Local, sequential Spring/JVM/native validation; no cloud or external services."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import platform
import shutil
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
OUT = ROOT / 'build/wasm-benchmark'
CONFIG = '''nanofaas:
  admin:
    runtime-config:
      enabled: true
  forecasting:
    enabled: true
    node-id: local
    provider: holt
    window: 1s
    max-age: 1m
'''
ARGS = ['--nanofaas.forecasting.enabled=true', '--nanofaas.forecasting.node-id=local',
        '--nanofaas.forecasting.window=1s', '--nanofaas.forecasting.max-age=1m',
        '--nanofaas.forecasting.provider=holt', '--nanofaas.admin.runtime-config.enabled=true']


def command(*args):
    return subprocess.check_output(args, cwd=ROOT, text=True).strip()


def build():
    OUT.mkdir(parents=True, exist_ok=True)
    for composition in ('java', 'wasm'):
        modules = 'forecasting,runtime-config,build-metadata' + (',forecasting-wasm' if composition == 'wasm' else '')
        init = str(HERE.relative_to(ROOT) / 'benchmark.gradle')
        for runtime in ('jvm', 'native'):
            started = time.monotonic()
            image = f'nanofaas/forecast-bench-{runtime}-{composition}:local'
            with (OUT / f'build-{runtime}-{composition}.log').open('w') as log:
                if runtime == 'jvm':
                    subprocess.run(['./gradlew', '-I', init, f'-PcontrolPlaneModules={modules}', ':control-plane:bootJar', '--console=plain'], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True)
                    staging = OUT / composition
                    staging.mkdir(exist_ok=True)
                    shutil.copy2(ROOT / 'platform/control-plane/build/libs/app.jar', staging / 'app.jar')
                    (staging / 'Dockerfile').write_text('FROM gcr.io/distroless/java25-debian13:nonroot\nWORKDIR /app\nCOPY app.jar /app/app.jar\nENV NANOFAAS_REGISTRY_PATH=/tmp/functions.json\nENTRYPOINT ["java","-XX:+UseSerialGC","-Xmx64m","-jar","/app/app.jar"]\n')
                    subprocess.run(['docker', 'build', '-t', image, str(staging)], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True)
                else:
                    subprocess.run(['docker', 'build', '--build-context', 'containerd_maven_repo=tools/native-java/empty-maven-repo',
                        '-f', 'tools/native-java/Dockerfile', '--build-arg', 'NATIVE_TASK=:control-plane:nativeCompile',
                        '--build-arg', 'NATIVE_BINARY=platform/control-plane/build/native/nativeCompile/control-plane',
                        '--build-arg', f'GRADLE_ARGS=-I {init} -PnanofaasBuildType=native -PcontrolPlaneModules={modules} -PnativeBuildMemory=6g -PnativeParallelism=4',
                        '--build-arg', 'NATIVE_AOT_CONFIG=' + base64.b64encode(CONFIG.encode()).decode(), '-t', image, '.'], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True)
            metadata = {'image': image, 'build_seconds': time.monotonic()-started,
                        'inspect': json.loads(command('docker', 'image', 'inspect', image))[0]}
            (OUT / f'image-{runtime}-{composition}.json').write_text(json.dumps(metadata, indent=2))
            print(f'BUILT {image}: {metadata["build_seconds"]:.1f}s', flush=True)


def run(repetitions):
    OUT.mkdir(parents=True, exist_ok=True)
    manifest = ROOT / 'platform/modules/forecasting-wasm/src/main/resources/wasm/holt-v1.manifest.json'
    (OUT / 'host.json').write_text(json.dumps({'host': platform.platform(), 'docker': json.loads(command('docker', 'info', '--format', '{{json .}}')),
        'revision': command('git', 'rev-parse', 'HEAD'), 'diff_sha256': hashlib.sha256(command('git', 'diff', 'HEAD').encode()).hexdigest(),
        'guest': json.loads(manifest.read_text()), 'repetitions': repetitions, 'cpus': 4, 'heap': '64m'}, indent=2))
    for repetition in range(1, repetitions+1):
        for runtime in ('jvm', 'native'):
            for composition, mode in (('java','java'), ('wasm','java'), ('wasm','wasm')):
                label = f'{runtime}-{composition}-{mode}-{repetition}'
                name = 'wasm-bench-' + uuid.uuid4().hex[:12]
                image = f'nanofaas/forecast-bench-{runtime}-{composition}:local'
                print('START ' + label, flush=True)
                prefix = ['-Xmx64m'] if runtime == 'native' else []
                samples = []
                started = time.monotonic()
                try:
                    command('docker', 'run', '-d', '--cpus=4', '--name', name, image, *prefix, *ARGS, '--benchmark.mode=' + mode)
                    while command('docker', 'inspect', '-f', '{{.State.Running}}', name) == 'true':
                        if time.monotonic()-started > 180:
                            raise TimeoutError(label)
                        top = command('docker', 'top', name, '-eo', 'pid,rss,comm').splitlines()[1:]
                        samples.append({'seconds': time.monotonic()-started, 'rss_kib': sum(int(line.split()[1]) for line in top)})
                        time.sleep(1)
                    logs = command('docker', 'logs', name)
                    (OUT / (label + '.log')).write_text(logs)
                    events = [json.loads(line.removeprefix('BENCH ')) for line in logs.splitlines() if line.startswith('BENCH ')]
                    state = json.loads(command('docker', 'inspect', name))[0]['State']
                    result = {'label': label, 'image': image, 'runtime': runtime, 'composition': composition, 'mode': mode,
                              'repetition': repetition, 'state': state, 'events': events, 'rss': samples}
                    (OUT / (label + '.json')).write_text(json.dumps(result, indent=2))
                    assert state['ExitCode'] == 0 and not state['OOMKilled'], state
                    assert events[-1] == {'kind': 'closed', 'ok': True}, events[-1:]
                    assert sum(e['kind']=='micro' for e in events) == 9, events
                    print(f'PASS {label} peak RSS {max(s["rss_kib"] for s in samples)/1024:.1f} MiB', flush=True)
                finally:
                    subprocess.run(['docker', 'rm', '-f', name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['build', 'run'])
    parser.add_argument('--repetitions', type=int, default=3)
    args = parser.parse_args()
    if args.repetitions < 1:
        parser.error('repetitions must be positive')
    build() if args.action == 'build' else run(args.repetitions)
