"""Rebuild the pinned guest, or verify its checked-in source/artifact manifest."""
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import tomllib

GUEST = Path(__file__).resolve().parent
ARTIFACT = GUEST.parent / 'src/main/resources/wasm/holt-v1.wasm'
MANIFEST = ARTIFACT.with_suffix('.manifest.json')
SOURCES = ('Cargo.toml', 'Cargo.lock', 'rust-toolchain.toml', '.cargo/config.toml', 'src/lib.rs', 'build_guest.py')


def snapshot():
    return {'abi': 1, 'target': 'wasm32-unknown-unknown',
            'toolchain': tomllib.loads((GUEST / 'rust-toolchain.toml').read_text())['toolchain']['channel'],
            'sources': {name: hashlib.sha256((GUEST / name).read_bytes()).hexdigest() for name in SOURCES},
            'sha256': hashlib.sha256(ARTIFACT.read_bytes()).hexdigest()}


def verify():
    if json.loads(MANIFEST.read_text()) != snapshot():
        raise ValueError('WASM artifact is stale or modified; run compileWasm and review the generated artifact')


def compile_guest(check):
    toolchain = tomllib.loads((GUEST / 'rust-toolchain.toml').read_text())['toolchain']['channel']
    rustc = subprocess.check_output(['rustup', 'which', '--toolchain', toolchain, 'rustc'], text=True).strip()
    flags = tomllib.loads((GUEST / '.cargo/config.toml').read_text())['target']['wasm32-unknown-unknown']['rustflags']
    with tempfile.TemporaryDirectory(prefix='nanofaas-wasm-') as directory:
        env = dict(os.environ, RUSTC=rustc, CARGO_TARGET_DIR=directory,
                   RUSTFLAGS=shlex.join([*flags, '--remap-path-prefix=' + str(GUEST) + '=/guest']))
        for key in ('CARGO_ENCODED_RUSTFLAGS', 'RUSTC_WRAPPER', 'RUSTC_WORKSPACE_WRAPPER'):
            env.pop(key, None)
        subprocess.run(['rustup', 'run', toolchain, 'cargo', 'build', '--locked', '--release',
                        '--target', 'wasm32-unknown-unknown'], cwd=GUEST, env=env, check=True)
        result = (Path(directory) / 'wasm32-unknown-unknown/release/nanofaas_holt_wasm.wasm').read_bytes()
        if check:
            verify()
            if result != ARTIFACT.read_bytes(): raise ValueError('WASM rebuild is not byte-identical')
        else:
            ARTIFACT.parent.mkdir(parents=True, exist_ok=True)
            ARTIFACT.write_bytes(result)
            MANIFEST.write_text(json.dumps(snapshot(), indent=2) + '\n')


if __name__ == '__main__':
    action = sys.argv[1] if len(sys.argv) == 2 else ''
    if action == 'verify': verify()
    elif action in ('compile', 'check'): compile_guest(action == 'check')
    else: raise SystemExit('usage: build_guest.py compile|verify|check')
    print('WASM artifact ' + action + ': PASS')
