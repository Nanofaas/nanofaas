"""Bounded real-clock HTTP fault profiles using the existing packaged probe runner."""
import concurrent.futures
import http.server
import json
import hashlib
import pathlib
import subprocess
import sys
import threading
import time
from http_runner import encoded, request, write_json, proc_snapshot


def fault_backend():
    lock = threading.Lock()
    state = dict(active=0, attempts=0, disconnected=0, by_id={})

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_GET(self):
            with lock:
                data = encoded(state)
            self.send_response(200)
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_POST(self):
            payload = json.loads(self.rfile.read(int(self.headers['Content-Length'])))['input']
            identifier = payload['id']
            with lock:
                state['active'] += 1
                state['attempts'] += 1
                attempt = state['by_id'][identifier] = state['by_id'].get(identifier, 0)+1
            try:
                time.sleep(payload.get('delayMs', 0)/1000)
                output = {'id': identifier, 'data': 'x'*payload.get('outputBytes', 0)}
                if self.path.startswith('/v1/functions/'):
                    output = dict(executionId='remote-'+identifier, status='success', output=output)
                data = encoded(output)
                self.send_response(500 if payload.get('errorFirst') and attempt == 1 else 200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(data)))
                self.end_headers()
                self.wfile.write(data)
            except (BrokenPipeError, ConnectionResetError):
                with lock:
                    state['disconnected'] += 1
            finally:
                with lock:
                    state['active'] -= 1

    http.server.ThreadingHTTPServer(('127.0.0.1', 18983), Handler).serve_forever()


def profiles(work, selection):
    folder = work/'profiles'/selection
    folder.mkdir(parents=True, exist_ok=False)
    jar = work/'artifacts'/('B-'+selection+'.jar')
    java = ['java', '-Xms128m', '-Xmx512m', '-XX:ActiveProcessorCount=4',
            '-Dloader.main=P19Probe', '-Dloader.path='+str(work/'probe'), '-Dp19.probePort=18982',
            '-cp', str(jar), 'org.springframework.boot.loader.launch.PropertiesLauncher',
            '--server.port=18980', '--management.server.port=18981', '--logging.level.root=WARN',
            '--nanofaas.registry.path='+str(folder/'catalog.json'), '--nanofaas.execution-store.ttl=2s',
            '--nanofaas.execution-store.sync-ttl=2s', '--nanofaas.execution-store.max-lifetime=3s',
            '--nanofaas.http-client.max-idle-time-ms=1000', '--nanofaas.http-client.pool-inactivity-ms=1000',
            '--nanofaas.http-client.inactive-pool-dispose-interval-ms=500',
            '--nanofaas.admin.runtime-config.enabled=true', '--sync-queue.admission-enabled=false']
    samples, cases = [], []
    stopping = threading.Event()
    backend = server = sampler = None
    log = (folder/'server.log').open('wb')
    result = dict(schema='p19-short-http-v1', selection=selection, command=java, valid=False,
                  started_ns=time.monotonic_ns(), max_duration_s=120, sampling_s=.05,
                  revision='6d08303371d803f44187ec5f4e37827d54fec597', jar_sha256=hashlib.sha256(jar.read_bytes()).hexdigest())
    names = set()

    def sample(phase):
        value = request(18982, 'GET', '/snapshot')[1]
        value.update(phase=phase, backend=request(18983, 'GET', '/')[1])
        samples.append(value)
        return value

    def periodic():
        while not stopping.wait(.05):
            try:
                sample('traffic')
            except Exception as error:
                samples.append(dict(status='unavailable', reason=repr(error), monotonic_ns=time.monotonic_ns()))

    def register(name, **extra):
        spec = dict(name=name, image='unused', executionMode='EXTERNAL', endpointUrl='http://127.0.0.1:18983/',
                    timeoutMs=5000, concurrency=4, queueSize=8, maxRetries=0)
        spec.update(extra)
        status, response = request(18980, 'POST', '/v1/functions', spec)
        assert status == 201, (status, response)
        names.add(name)
        return spec

    def invoke(name, payload, key=None, timeout=None):
        headers = {'Idempotency-Key': key} if key else {}
        if timeout is not None:
            headers['X-Timeout-Ms'] = str(timeout)
        start = time.monotonic_ns()
        status, response = request(18980, 'POST', '/v1/functions/'+name+':invoke', {'input': payload}, headers)
        return dict(status=status, response=response, sent_ns=start, done_ns=time.monotonic_ns())

    def drain(case):
        deadline = time.monotonic()+12
        while time.monotonic() < deadline:
            point = sample(case+'-drain')
            if all(point['populations'][key]['value'] == 0 for key in
                   ['live', 'execution_reservations', 'canonical_input_bytes', 'physical_input_copy_bytes', 'waiters', 'retained_waiters']) and point['backend']['active'] == 0:
                assert point['live_owners']['open_futures'] == 0, point
                return point
            time.sleep(.05)
        raise AssertionError('physical drain deadline: '+case)

    try:
        backend = subprocess.Popen([sys.executable, __file__, 'backend'], stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
        server = subprocess.Popen(java, stdout=log, stderr=subprocess.STDOUT)
        deadline = time.monotonic()+45
        while time.monotonic() < deadline:
            try:
                if request(18982, 'GET', '/alloc')[0] == 200:
                    break
            except OSError:
                time.sleep(.1)
        else:
            raise AssertionError('profile startup deadline')
        result['processes'] = {'server': proc_snapshot(server.pid), 'backend': proc_snapshot(backend.pid)}
        sample('baseline')
        sampler = threading.Thread(target=periodic)
        sampler.start()
        modes = [False, True] if selection == 'all' else [False]
        for offload in modes:
            name = 'p19-t3-'+str(offload).lower()
            spec = register(name, **({'offload': {'enabled': True, 'targetUrl': 'http://127.0.0.1:18983', 'mode': 'always'}} if offload else {}))
            payload = {'id': name, 'delayMs': 800}
            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                long = pool.submit(invoke, name, payload, name, 2500)
                time.sleep(.15)
                short = pool.submit(invoke, name, payload, name, 50)
                short_result = short.result()
                during = sample(name+'-short-expired')
                assert during['live_owners']['open_futures'] == 1, during
                assert not long.done()
                long_result = long.result()
            assert short_result['response']['status'] == 'timeout' and long_result['response']['status'] == 'success', (short_result, long_result)
            assert short_result['response']['executionId'] == long_result['response']['executionId']
            replay = invoke(name, payload, name, 1000)
            assert replay['response']['executionId'] == long_result['response']['executionId']
            assert request(18983, 'GET', '/')[1]['by_id'][name] == 1
            cases.append(dict(id='T3', offload=offload, spec=spec, offered=3, unique_admitted=1, refused=0, useful_unique_successes=1,
                              short=short_result, long=long_result, replay=replay))
            drain(name)
        name = 'p19-t4-retry'
        spec = register(name, maxRetries=1)
        reply = invoke(name, {'id': name, 'delayMs': 200, 'errorFirst': True})
        assert reply['status'] == 200 and request(18983, 'GET', '/')[1]['by_id'][name] == 2, reply
        cases.append(dict(id='T4-retry', spec=spec, offered=1, unique_admitted=1, refused=0, useful_unique_successes=1, reply=reply))
        drain(name)
        name = 'p19-t4-expiry'
        spec = register(name, timeoutMs=10000)
        with concurrent.futures.ThreadPoolExecutor() as pool:
            future = pool.submit(invoke, name, {'id': name, 'delayMs': 6000}, name, 9000)
            time.sleep(.3)
            sample(name+'-active')
            if selection.startswith('sync-queue,'):
                current = request(18980, 'GET', '/v1/admin/runtime-config')[1]
                status, response = request(18980, 'PATCH', '/v1/admin/runtime-config/sync-queue',
                                           {'expectedRevision': current['revision'], 'values': {'enabled': False}})
                assert status == 200, (status, response)
                cases.append(dict(id='T4-sync-toggle-during-work', status=status, response=response))
            reply = future.result(timeout=12)
        assert reply['response']['status'] in ['timeout', 'error'], reply
        cases.append(dict(id='T4-administrative-expiry', spec=spec, offered=1, unique_admitted=1, refused=0, useful_unique_successes=0, reply=reply))
        drain(name)
        for i in range(3):
            name = 'p19-t5-'+str(i)
            register(name)
            with concurrent.futures.ThreadPoolExecutor() as pool:
                old = pool.submit(invoke, name, {'id': name+'-old', 'delayMs': 700})
                time.sleep(.15)
                sample(name+'-old-active')
                removed, _ = request(18980, 'DELETE', '/v1/functions/'+name)
                assert 200 <= removed < 300
                register(name)
                reply = invoke(name, {'id': name+'-new'}, name+'-new')
                assert reply['status'] == 200 and reply['response']['output']['id'] == name+'-new'
                old_reply = old.result()
                sample(name+'-old-released')
                replay = invoke(name, {'id': name+'-new'}, name+'-new')
                assert replay['response']['executionId'] == reply['response']['executionId']
            request(18980, 'DELETE', '/v1/functions/'+name)
            names.remove(name)
            cases.append(dict(id='T5-remove-reregister-with-old-response', name=name, offered=3,
                              unique_admitted=2, refused=0, useful_unique_successes=1+int(old_reply['response'].get('status') == 'success'), reply=reply, old=old_reply, replay=replay))
            drain(name)
        name = 'p19-t7'
        spec = register(name)
        with concurrent.futures.ThreadPoolExecutor() as pool:
            future = pool.submit(invoke, name, {'id': name, 'padding': 'x'*262144, 'delayMs': 300, 'outputBytes': 262144})
            time.sleep(.1)
            sample('T7-large-active')
            reply = future.result()
        assert reply['status'] == 200
        oversized = invoke(name, {'id': name+'-over', 'padding': 'x'*1048576})
        assert oversized['status'] == 413, oversized
        cases.append(dict(id='T7-input-output', spec=spec, offered=2, unique_admitted=1, useful_unique_successes=1, refused=1,
                          success_status=reply['status'], refusal_status=oversized['status']))
        drain(name)
        for name in names:
            request(18980, 'DELETE', '/v1/functions/'+name)
        time.sleep(4)
        point = sample('T9-post-policy-removed')
        assert point['populations']['keys']['value'] == point['populations']['outcomes']['value'] == 0
        result.update(cases=cases, post_gc=request(18982, 'GET', '/gc')[1], valid=True)
    except BaseException as error:
        result['failure'] = repr(error)
        raise
    finally:
        stopping.set()
        if sampler:
            sampler.join(timeout=20)
        for child in [server, backend]:
            if child:
                child.terminate()
                try:
                    child.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait(timeout=5)
        result.update(ended_ns=time.monotonic_ns(), cases=cases,
                      shutdown={role: not pathlib.Path(f'/proc/{child.pid}').exists() if child else True for role, child in [('server', server), ('backend', backend)]})
        write_json(folder/'result.json', result)
        write_json(folder/'populations.json', samples)
        log.close()


if __name__ == '__main__':
    if sys.argv[1] == 'backend':
        fault_backend()
    else:
        profiles(pathlib.Path(sys.argv[1]), sys.argv[2])
