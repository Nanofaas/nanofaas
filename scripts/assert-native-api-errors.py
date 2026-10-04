#!/usr/bin/env python3
"""Execute the released native binary and verify management error JSON over HTTP."""
import argparse
import json
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request


def request(port, method, path, payload=None):
    data = None if payload is None else json.dumps(payload).encode()
    req = urllib.request.Request(f'http://127.0.0.1:{port}{path}', data=data,
        headers={'Content-Type': 'application/json'}, method=method)
    try:
        response = urllib.request.urlopen(req, timeout=2)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, response.headers.get_content_type(), json.loads(response.read())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('binary', type=Path)
    args = parser.parse_args()
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        port = listener.getsockname()[1]
    with tempfile.TemporaryDirectory(prefix='native-api-errors-') as folder:
        log = Path(folder) / 'runtime.log'
        with log.open('w+') as output:
            process = subprocess.Popen([str(args.binary.resolve()), f'--server.port={port}',
                f'--nanofaas.registry.path={folder}/registry.json', '--logging.level.root=WARN'],
                stdout=output, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + 60
                while True:
                    if process.poll() is not None:
                        raise RuntimeError(f'native runtime exited {process.returncode}')
                    try:
                        missing = request(port, 'GET', '/v1/functions/native-contract-absent')
                        break
                    except (urllib.error.URLError, TimeoutError):
                        if time.monotonic() >= deadline:
                            raise TimeoutError('native HTTP startup deadline')
                        time.sleep(0.1)
                status, content_type, body = missing
                assert (status, content_type) == (404, 'application/json'), missing
                assert body == {'error': 'FUNCTION_NOT_FOUND', 'message': 'Function not found'}, missing
                invalid = request(port, 'POST', '/v1/functions', {})
                status, content_type, body = invalid
                assert (status, content_type) == (400, 'application/json'), invalid
                assert body['error'] == 'VALIDATION_ERROR' and body['message'], invalid
                assert body['details'], invalid
                print('Native HTTP management errors: 404 and validation 400 verified')
            except BaseException:
                output.flush()
                print(log.read_text())
                raise
            finally:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)


if __name__ == '__main__':
    main()
