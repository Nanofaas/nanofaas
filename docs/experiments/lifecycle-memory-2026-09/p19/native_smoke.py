"""Actual minimal/managed native invocation, replay, removal and bounded shutdown."""
import pathlib
import subprocess
import sys
import time
from http_runner import request, write_json
from contract import REVISIONS

# A dependency-free managed function fixture, not a numerical SDK benchmark.
ECHO = '''import http.server,json
class H(http.server.BaseHTTPRequestHandler):
 def do_GET(self):
  b=b'{"status":"UP"}';self.send_response(200);self.send_header('Content-Length',str(len(b)));self.end_headers();self.wfile.write(b)
 def do_POST(self):
  d=json.loads(self.rfile.read(int(self.headers['Content-Length'])));b=json.dumps(d['input']).encode();self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(b)));self.end_headers();self.wfile.write(b)
http.server.ThreadingHTTPServer(('0.0.0.0',8080),H).serve_forever()
'''


def native_errors(log):
    return [line for line in log.splitlines() if any(marker in line for marker in
            ['MissingReflectionRegistrationError', 'UnsupportedFeatureError', 'Failed to invoke custom destroy method'])]


def smoke(work, selection):
    folder = work / 'smoke' / ('native-' + selection)
    folder.mkdir(parents=True, exist_ok=False)
    image = 'nanofaas/control-plane:p19-' + REVISIONS['B'][:8] + '-' + selection
    name = 'nanofaas-p19-smoke-' + selection + '-' + str(time.time_ns())
    function = name + '-fn'
    command = ['docker', 'run', '-d', '--name', name, '--network=host', '--user=0', '--memory=512m', '--cpus=4']
    if selection == 'container':
        command += ['-v', '/var/run/docker.sock:/var/run/docker.sock']
    command += [image, '--server.port=18880', '--management.server.port=18881',
                '--nanofaas.registry.path=/tmp/' + name + '.json', '--logging.level.root=WARN']
    if selection == 'container':
        # Distroless has no Docker CLI; use the existing in-process adapter.
        command += ['--nanofaas.container-local.runtime-adapter=docker-java']
    record = dict(command=command, valid=False, selection=selection, started_ns=time.monotonic_ns())
    cid = backend = None
    try:
        backend = subprocess.Popen([sys.executable, str(pathlib.Path(__file__).with_name('http_runner.py')), 'backend', '18883'],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        cid = subprocess.check_output(command, text=True, timeout=30).strip()
        record['container_id'] = cid
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            try:
                status, health = request(18881, 'GET', '/actuator/health')
                if status == 200:
                    break
            except OSError:
                pass
            time.sleep(.2)
        else:
            raise RuntimeError('native health deadline')
        record['health'] = health
        status, spec = request(18880, 'GET', '/openapi.yaml')
        record['openapi_status'] = status
        assert status == 200 and 'openapi:' in spec
        spec = dict(name=function, image='python:3.12-slim', timeoutMs=10000, maxRetries=0, concurrency=2)
        if selection == 'container':
            spec.update(executionMode='DEPLOYMENT', command=['python', '-c', ECHO])
        else:
            spec.update(executionMode='EXTERNAL', endpointUrl='http://127.0.0.1:18883/')
        status, registered = request(18880, 'POST', '/v1/functions', spec)
        record['registration'] = dict(status=status, response=registered)
        assert status == 201, record['registration']
        for phase in ['invocation', 'replay']:
            status, response = request(18880, 'POST', '/v1/functions/' + function + ':invoke',
                                       {'input': {'native': 'ok'}}, {'Idempotency-Key': 'native-fixed-key'})
            record[phase] = dict(status=status, response=response)
            assert status == 200 and response['output'] == {'native': 'ok'}, record[phase]
        assert record['replay']['response']['executionId'] == record['invocation']['response']['executionId']
        record['metrics_after_replay'] = request(18881, 'GET', '/actuator/prometheus')[1]
        status, removed = request(18880, 'DELETE', '/v1/functions/' + function)
        record['removal_status'] = status
        assert 200 <= status < 300, (status, removed)
        record['owned_function_containers_after_remove'] = subprocess.check_output(
            ['docker', 'ps', '-aq', '--filter', 'label=io.nanofaas.function=' + function], text=True, timeout=15).split()
        assert not record['owned_function_containers_after_remove']
        record['image_inspect'] = subprocess.check_output(['docker', 'image', 'inspect', image], text=True)
        record['checks_passed'] = True
    except BaseException as error:
        record['failure'] = repr(error)
        raise
    finally:
        if cid:
            try:
                request(18880, 'DELETE', '/v1/functions/' + function)
            except Exception as error:
                record['cleanup_request_error'] = repr(error)
            record['stop_exit'] = subprocess.run(['docker', 'stop', '--time', '20', cid], capture_output=True, timeout=30).returncode
            (folder / 'output.log').write_bytes(subprocess.check_output(['docker', 'logs', cid], stderr=subprocess.STDOUT))
            record['remove_exit'] = subprocess.run(['docker', 'rm', cid], capture_output=True, timeout=15).returncode
        if backend:
            backend.terminate()
            try:
                backend.wait(timeout=5)
            except subprocess.TimeoutExpired:
                backend.kill()
                backend.wait(timeout=5)
        record['ended_ns'] = time.monotonic_ns()
        record['application_errors'] = native_errors((folder/'output.log').read_text()) if (folder/'output.log').exists() else ['native log unavailable']
        record['valid'] = record.get('checks_passed', False) and record.get('stop_exit') == 0 and record.get('remove_exit') == 0 and not record['application_errors']
        write_json(folder / 'result.json', record)
    assert record['valid'], record
    print('native-' + selection, record['valid'], flush=True)


if __name__ == '__main__':
    for selection in (sys.argv[2:] or ['none', 'container']):
        smoke(pathlib.Path(sys.argv[1]), selection)
