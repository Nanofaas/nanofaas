"""Execute shared failure definitions through the runtime and real callback HTTP I/O."""
import asyncio
import importlib
import json
from pathlib import Path
import threading
import subprocess
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest
from nanofaas.sdk import decorator
from nanofaas.sdk.response import HandlerResponse
import nanofaas.runtime.app as runtime_app

subprocess.run([sys.executable, str(Path(__file__).parents[2] / "runtime-contract/validate_saturation_wire_corpus.py"), str(Path(__file__).parents[2] / "runtime-contract/failure-wire-corpus.json")], check=True, timeout=10)
CORPUS = json.loads((Path(__file__).parents[2] / 'runtime-contract/failure-wire-corpus.json').read_text())


@pytest.mark.parametrize('case', CORPUS['contractDefinitions'])
def test_shared_failure_case(case, monkeypatch):
    expected = dict(CORPUS['contractDefinitions'][case])
    difference = CORPUS['knownDifferences'].get('python', {}).get(case, {})
    if difference:
        expected['errorCode'] = difference['errorCode']
    config = CORPUS['config']
    calls = []
    release = threading.Event()

    class Callback(BaseHTTPRequestHandler):
        def do_POST(self):
            payload = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            calls.append((payload, self.headers.get('X-Dispatch-Attempt'), self.headers.get('X-Trace-Id'), self.path))
            if case == 'callback-io-timeout':
                release.wait(config['deadlineMs'] / 1000)
                return
            self.send_response(expected['callbackStatus'] or 204)
            self.end_headers()
        def log_message(self, *_): pass

    server = ThreadingHTTPServer(('127.0.0.1', 0), Callback)
    server.daemon_threads = True
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    monkeypatch.setenv('CALLBACK_URL', f'http://127.0.0.1:{server.server_port}')
    monkeypatch.setenv('NANOFAAS_CALLBACK_MAX_ATTEMPTS', str(config['callbackMaxAttempts']))
    monkeypatch.setenv('NANOFAAS_CALLBACK_ATTEMPT_TIMEOUT', str(config['callbackAttemptTimeoutMs']))
    monkeypatch.setenv('NANOFAAS_BODY_READ_TIMEOUT', str(config['bodyReadTimeoutMs']))
    runtime = importlib.reload(runtime_app)
    started = []

    @decorator.nanofaas_function
    async def handler(_):
        started.append(True)
        if case == 'envelope-serialization-failure':
            return HandlerResponse(object(), status_code=201)
        return CORPUS['successOutput']

    class Request:
        headers = {}
        async def stream(self):
            yield b'{"input":'
            if case == 'ingress-io-timeout':
                await asyncio.Event().wait()
            yield b'null}'

    async def run():
        try:
            response = await runtime.invoke(Request(), runtime.BackgroundTasks(),
                x_execution_id=config['executionId'], x_trace_id=config['traceId'],
                x_dispatch_attempt=str(config['dispatchAttempt']))
            body = json.loads(response.body)
            assert response.status_code == expected['httpStatus']
            assert bool(started) == expected['handlerStarted']
            if expected['errorCode']:
                assert body['error']['code'] == expected['errorCode']
                assert body['error']['message']
            await runtime._runtime_work.shutdown(config['deadlineMs'] / 1000)
            snapshot = runtime._runtime_work.snapshot()
            assert snapshot.pending_callbacks == snapshot.pending_callback_bytes == snapshot.active_handlers == snapshot.active_callback_workers == 0
            assert len(calls) == expected['callbackAttempts']
            for payload, attempt, trace, path in calls:
                assert config['executionId'] in path
                assert attempt == str(config['dispatchAttempt'])
                assert trace == config['traceId']
                if expected['errorCode']:
                    assert payload == {'success': False, 'output': None, 'error': body['error']}
                else:
                    assert payload == {'success': True, 'output': CORPUS['successOutput'], 'error': None}
        finally:
            await runtime._runtime_work.shutdown(0.5)

    try:
        asyncio.run(asyncio.wait_for(run(), config['deadlineMs'] / 1000))
    finally:
        release.set()
        server.shutdown()
        server.server_close()
        thread.join(timeout=1)
        monkeypatch.undo()
        importlib.reload(runtime_app)
