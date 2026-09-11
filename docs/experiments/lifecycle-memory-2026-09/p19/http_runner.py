"""Separate-process EXTERNAL HTTP cohort, observer, backend and lossless records.

No claim of maximum sustainable throughput. Ordinary windows use fixed arrivals;
explicit GC, diagnostics, registration and shutdown lie outside those windows.
"""
import argparse
import concurrent.futures
import gzip
import hashlib
import http.client
import http.server
import json
import os
import signal
import pathlib
import subprocess
import sys
import threading
import time
from contract import summarize, percentiles, terminal_status


def encoded(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':')).encode()


def write_json(path, value):
    path.write_bytes(encoded(value) + b'\n')


def request(port, method, path, body=None, headers=None, connection=None):
    client = connection or http.client.HTTPConnection('127.0.0.1', port, timeout=15)
    client.request(method, path, encoded(body) if body is not None else None,
                   {'Content-Type': 'application/json', **(headers or {})})
    response = client.getresponse()
    raw = response.read()
    status = response.status
    if connection is None:
        client.close()
    try:
        return status, json.loads(raw) if raw else None
    except ValueError:
        return status, raw.decode()


def backend(port):
    events = []
    lock = threading.Lock()

    class Handler(http.server.BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'

        def log_message(self, *args):
            pass

        def do_GET(self):
            with lock:
                body = encoded(events)
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_POST(self):
            data = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            body = encoded(data['input'])
            with lock:
                events.append(dict(execution_id=self.headers.get('X-Execution-Id'),
                                   attempt=self.headers.get('X-Dispatch-Attempt'), terminal_observed_ns=time.monotonic_ns()))
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    http.server.ThreadingHTTPServer(('127.0.0.1', port), Handler).serve_forever()


def proc_snapshot(pid):
    result = {'pid': pid}
    try:
        result['start_ticks'] = int(pathlib.Path(f'/proc/{pid}/stat').read_text().split(') ')[1].split()[19])
        result['boot_id'] = pathlib.Path('/proc/sys/kernel/random/boot_id').read_text().strip()
    except OSError as error:
        result['identity_status'] = {'status': 'unavailable', 'reason': str(error)}
    for name in ['status', 'smaps_rollup', 'schedstat']:
        try:
            result[name] = pathlib.Path(f'/proc/{pid}/{name}').read_text()
        except OSError as error:
            result[name] = {'status': 'unavailable', 'reason': str(error)}
    try:
        result['fd_count'] = len(list(pathlib.Path(f'/proc/{pid}/fd').iterdir()))
        result['socket_fd_count'] = sum(os.readlink(p).startswith('socket:') for p in pathlib.Path(f'/proc/{pid}/fd').iterdir())
    except OSError as error:
        result['fds'] = {'status': 'unavailable', 'reason': str(error)}
    return result


def run(args):
    folder = args.output
    folder.mkdir(parents=True, exist_ok=False)
    port, probe, back = 18080, 18082, 18083
    config = ['--server.port=18080', '--management.server.port=18081',
              '--nanofaas.registry.path=' + str(folder / 'catalog.json'),
              '--logging.level.root=WARN']
    # T2 is a bounded-retention profile; T1 retains published production defaults.
    if args.scenario == 'async':
        config += ['--nanofaas.execution-store.ttl=2s', '--nanofaas.execution-store.sync-ttl=2s',
                   '--nanofaas.execution-store.max-lifetime=3s', '--nanofaas.execution-store.max-outcome-bytes=1048576']
    java = ['java', '-Xms256m', '-Xmx512m', '-XX:+UseG1GC', '-XX:ActiveProcessorCount=4',
            '-Dp19.observeTerminals=' + str(args.scenario == 'async').lower(),
            '-Xlog:gc:file=' + str(folder / 'gc.log'), '-Dp19.probePort=18082',
            '-Dloader.main=P19Probe', '-Dloader.path=' + str(args.probe), '-cp', str(args.jar),
            'org.springframework.boot.loader.launch.PropertiesLauncher', *config]
    config_identity = hashlib.sha256(encoded([c if not c.startswith('--nanofaas.registry.path=') else '--nanofaas.registry.path=<isolated>' for c in config])).hexdigest()
    workload = dict(schema='p19-http-workload-v1', scenario=args.scenario, warmup=args.warmup,
                    offered=args.count, rate_per_s=args.rate, outstanding_bound=32,
                    seed=19, sync_input={'id': 'cohort-index'}, async_shapes=['flat', 'deep20', 'wide4096', 'large262144'])
    write_json(folder / 'workload.json', workload)
    backend_log = (folder / 'backend.log').open('wb')
    server_log = (folder / 'server.log').open('wb')
    backend_process = server = None
    sampler_stop = threading.Event()
    samples = []
    checkpoints = []
    rows = []
    thread_local = threading.local()
    result = dict(schema='p19-http-run-v1', label=args.label, revision=args.revision,
                  profile=args.profile, scenario=args.scenario, java_command=java,
                  generator_pid=os.getpid(), process_start_ns=time.monotonic_ns(),
                  jar_sha256=hashlib.sha256(args.jar.read_bytes()).hexdigest(),
                  workload=hashlib.sha256(encoded(workload)).hexdigest(), config=config_identity,
                  valid=False)

    def sample():
        while not sampler_stop.is_set():
            try:
                observation = request(probe, 'GET', '/snapshot')[1]
                observation['prometheus'] = request(18081, 'GET', '/actuator/prometheus')[1]
                samples.append(observation)
            except Exception as error:
                samples.append({'status': 'unavailable', 'reason': str(error)})
            sampler_stop.wait(args.sample_seconds or 1)

    def checkpoint(phase):
        status, observation = request(probe, 'GET', '/gc')
        assert status == 200 and observation['gc_verified'], observation
        observation['phase'] = phase
        observation['prometheus'] = request(18081, 'GET', '/actuator/prometheus')[1]
        observation['processes'] = {role: proc_snapshot(pid) for role, pid in
                                    [('control-plane', server.pid), ('backend', backend_process.pid), ('generator', os.getpid())]}
        checkpoints.append(observation)
        write_json(folder / 'checkpoints.json', checkpoints)
        histogram = subprocess.run(['jcmd', str(server.pid), 'GC.class_histogram', '-all'], capture_output=True, timeout=30)
        (folder / (phase + '-histogram.txt')).write_bytes(histogram.stdout + histogram.stderr)
        assert histogram.returncode == 0, 'histogram collector failed'

    def invoke(index, intended_ns, warm=False):
        if not hasattr(thread_local, 'connection'):
            thread_local.connection = http.client.HTTPConnection('127.0.0.1', port, timeout=15)
        identifier = ('warm-' if warm else 'measure-') + str(index)
        payload = {'id': identifier}
        shape = 'flat'
        if args.scenario == 'async':
            shape = ['flat', 'deep', 'wide', 'large'][index % 4]
            value = ['alpha', 7, True]
            if shape == 'deep':
                value = 'leaf'
                for _ in range(20):
                    value = [value]
            elif shape == 'wide':
                value = list(range(4096))
            elif shape == 'large':
                value = 'x' * 262144
            payload['shape'] = value
        row = dict(id=identifier, shape=shape, intended_ns=intended_ns, sent_ns=time.monotonic_ns(),
                   admitted=False, success=False, execution_id=None, status=None)
        try:
            status, response = request(port, 'POST', '/v1/functions/p19:' + ('enqueue' if args.scenario == 'async' else 'invoke'),
                                       {'input': payload}, {'Idempotency-Key': identifier} if args.scenario == 'async' else {},
                                       thread_local.connection)
            ack = time.monotonic_ns()
            row.update(status=status, ack_ns=ack, response=response if status >= 400 else None)
            if 200 <= status < 300:
                row['admitted'] = True
                row['execution_id'] = response['executionId']
                if args.scenario == 'async':
                    row['polls'] = 0
                    deadline = time.monotonic() + 10
                    while time.monotonic() < deadline:
                        _, response = request(probe, 'GET', '/terminal/' + row['execution_id'])
                        row['polls'] += 1
                        if terminal_status(response):
                            row['terminal_observed_ns'] = response['terminal_observed_ns']
                            break
                        time.sleep(.005)
                row['success'] = response is not None and (response.get('output_id') == identifier and response.get('status') == 'success'
                                                           if args.scenario == 'async' else response.get('output') == payload)
                row['terminal_failure'] = terminal_status(response) in ['error', 'timeout']
                if args.scenario == 'async' and row['success']:
                    row['initial_outcome_status'] = request(port, 'GET', '/v1/executions/' + row['execution_id'], connection=thread_local.connection)[0]
                if not row['success']:
                    row['unexpected_response'] = response
        except Exception as error:
            row['transport_error'] = repr(error)
            thread_local.connection.close()
        row['done_ns'] = time.monotonic_ns()
        row['latency_ns'] = row['done_ns'] - row['sent_ns']
        row['arrival_latency_ns'] = row['done_ns'] - row['intended_ns']
        return row

    def cohort(count, warm):
        start = time.monotonic_ns()
        futures = []
        permits = threading.Semaphore(32)
        with concurrent.futures.ThreadPoolExecutor(max_workers=32) as pool:
            for index in range(count):
                intended = start + int(index * 1e9 / args.rate)
                time.sleep(max(0, (intended - time.monotonic_ns()) / 1e9))
                permits.acquire()
                future = pool.submit(invoke, index, intended, warm)
                future.add_done_callback(lambda _: permits.release())
                futures.append(future)
            records = [future.result() for future in futures]
        end = max(row['done_ns'] for row in records)
        return records, start, end

    try:
        backend_process = subprocess.Popen([sys.executable, __file__, 'backend', str(back)], stdout=backend_log, stderr=subprocess.STDOUT)
        server = subprocess.Popen(java, stdout=server_log, stderr=subprocess.STDOUT, cwd=folder)
        result.update(server_pid=server.pid, backend_pid=backend_process.pid,
                      processes={role: proc_snapshot(pid) for role, pid in [('server', server.pid), ('backend', backend_process.pid), ('generator', os.getpid())]})
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            if server.poll() is not None:
                raise RuntimeError('server exited: inspect server.log')
            try:
                if request(probe, 'GET', '/alloc')[0] == 200 and request(back, 'GET', '/')[0] == 200:
                    break
            except OSError:
                time.sleep(.1)
        else:
            raise RuntimeError('startup deadline')
        spec = dict(name='p19', image='unused', executionMode='EXTERNAL', endpointUrl='http://127.0.0.1:18083/',
                    timeoutMs=10000, concurrency=32, queueSize=512, maxRetries=0)
        status, registration = request(port, 'POST', '/v1/functions', spec)
        assert status == 201, (status, registration)
        write_json(folder / 'function.json', registration)
        config_document = dict(java_options=java[1:6],
                               application_options=[c if not c.startswith('--nanofaas.registry.path=') else '--nanofaas.registry.path=<isolated>' for c in config],
                               function=registration,
                               environment=dict(boot_id=pathlib.Path('/proc/sys/kernel/random/boot_id').read_text().strip(),
                                                affinity=sorted(os.sched_getaffinity(0)), python=sys.version,
                                                probe_sha256=hashlib.sha256((args.probe/'P19Probe.class').read_bytes()).hexdigest()))
        result.update(config_document=config_document, config=hashlib.sha256(encoded(config_document)).hexdigest(), workload_document=workload)
        warm_rows, warm_start, warm_end = cohort(args.warmup, True)
        with gzip.GzipFile(filename=str(folder / 'warmup-requests.jsonl.gz'), mode='wb', mtime=0) as stream:
            for row in warm_rows:
                stream.write(encoded(row) + b'\n')
        warm_summary = summarize(warm_rows, args.warmup, warm_end-warm_start)
        write_json(folder / 'warmup.json', warm_summary)
        assert warm_summary['unique_successes'] == args.warmup, warm_rows[:3]
        checkpoint('warmed_idle')
        sampler = threading.Thread(target=sample if args.sample_seconds else lambda: None)
        sampler.start()
        alloc_before = request(probe, 'GET', '/alloc')[1]
        rows, start, end = cohort(args.count, False)
        alloc_after = request(probe, 'GET', '/alloc')[1]
        sampler_stop.set()
        sampler.join()
        result.update(summarize(rows, args.count, end-start))
        result.update(start_ns=start, end_ns=end, elapsed_ns=end-start,
                      allocated_before=alloc_before, allocated_after=alloc_after,
                      allocated_bytes_per_success=(alloc_after['allocated_bytes']-alloc_before['allocated_bytes'])/result['unique_successes'] if result['unique_successes'] else None,
                      arrival_latency_ns=percentiles([r['arrival_latency_ns'] for r in rows if r['success']]),
                      generator_lag_ns=percentiles([r['sent_ns']-r['intended_ns'] for r in rows]))
        if args.scenario == 'async':
            result['admission_latency_ns'] = percentiles([r['ack_ns']-r['sent_ns'] for r in rows if r['admitted']])
            replay_row = next(row for row in reversed(rows) if row['shape'] == 'deep')
            assert replay_row['initial_outcome_status'] == 404, 'deep output must be conservatively declined'
            lost_status, _ = request(port, 'GET', '/v1/executions/' + replay_row['execution_id'])
            deep_payload = 'leaf'
            for _ in range(20):
                deep_payload = [deep_payload]
            replay_status, _ = request(port, 'POST', '/v1/functions/p19:enqueue',
                                       {'input': {'id': replay_row['id'], 'shape': deep_payload}}, {'Idempotency-Key': replay_row['id']})
            result['outcome_unavailable_replay'] = {'cause': 'conservatively_declined_deep_output', 'initial_outcome_status': 404,
                                        'outcome_status': lost_status, 'replay_status': replay_status,
                                        'id': replay_row['id'], 'execution_id': replay_row['execution_id']}
            assert lost_status == 404 and replay_status == 410, result['outcome_unavailable_replay']
        checkpoint('cohort_drained')
        status, events = request(back, 'GET', '/')
        write_json(folder / 'backend-executions.json', events)
        result['backend_attempts'] = len(events)
        assert len(events) == args.warmup + result['unique_successes'], 'attempt accounting'
        # Actual production SYNC TTL is 30s; allow idle-pool disposal's 5s scan.
        policy_wait = 36 if args.scenario == 'sync' else 6
        time.sleep(policy_wait)
        checkpoint('post_policy')
        request(port, 'DELETE', '/v1/functions/p19')
        checkpoint('removed')
        result['valid'] = result['unique_successes'] == args.count and result['unresolved'] == 0
        assert result['valid'], result
    except BaseException as error:
        result['failure'] = repr(error)
        raise
    finally:
        sampler_stop.set()
        for process in [server, backend_process]:
            if process is None:
                continue
            process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        result['shutdown'] = {role + suffix: value for role, process in [('server', server), ('backend', backend_process)]
                              for suffix, value in [('_exit', process.returncode if process else None),
                                                    ('_proc_absent', not pathlib.Path(f'/proc/{process.pid}').exists() if process else True)]}
        result['process_end_ns'] = time.monotonic_ns()
        write_json(folder / 'run.json', result)
        for name, data in [('requests', rows), ('populations', samples)]:
            with gzip.GzipFile(filename=str(folder / (name + '.jsonl.gz')), mode='wb', mtime=0) as stream:
                for item in data:
                    stream.write(encoded(item) + b'\n')
        server_log.close()
        backend_log.close()
    print(args.label, result['unique_successes'], result['success_latency_ns'], flush=True)


if __name__ == '__main__':
    if sys.argv[1] == 'backend':
        backend(int(sys.argv[2]))
    else:
        def interrupted(signum, frame):
            raise InterruptedError('runner received signal ' + str(signum))
        signal.signal(signal.SIGTERM, interrupted)
        parser = argparse.ArgumentParser()
        parser.add_argument('--jar', required=True, type=pathlib.Path)
        parser.add_argument('--probe', required=True, type=pathlib.Path)
        parser.add_argument('--output', required=True, type=pathlib.Path)
        parser.add_argument('--label', required=True)
        parser.add_argument('--revision', required=True)
        parser.add_argument('--profile', default='none')
        parser.add_argument('--scenario', choices=['sync', 'async'], default='sync')
        parser.add_argument('--warmup', type=int, default=3000)
        parser.add_argument('--count', type=int, default=10000)
        parser.add_argument('--rate', type=int, default=200)
        parser.add_argument('--sample-seconds', type=float, default=1)
        run(parser.parse_args())
