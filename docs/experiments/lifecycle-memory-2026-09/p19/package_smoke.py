"""Start/health/OpenAPI/stop each packaged profile; no cluster provisioning."""
import json
import pathlib
import subprocess
import sys
import time
from http_runner import request, write_json

work = pathlib.Path(sys.argv[1])
for jar in sorted((work / 'artifacts').glob('B-*.jar')):
    folder = work / 'smoke' / jar.stem
    folder.mkdir(parents=True, exist_ok=False)
    command = ['java', '-Xmx512m', '-XX:ActiveProcessorCount=4', '-jar', str(jar), '--server.port=18880',
               '--management.server.port=18881', '--logging.level.root=WARN', '--nanofaas.registry.path=' + str(folder / 'catalog.json')]
    with (folder / 'output.log').open('wb') as output:
        process = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT)
        record = dict(command=command, pid=process.pid, valid=False)
        try:
            deadline = time.monotonic() + 60
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError('startup exited')
                try:
                    status, health = request(18881, 'GET', '/actuator/health')
                    if status == 200:
                        break
                except OSError:
                    pass
                time.sleep(.2)
            else:
                raise RuntimeError('health deadline')
            record['health'] = health
            status, spec = request(18880, 'GET', '/openapi.yaml')
            record['openapi_status'] = status
            record['valid'] = status == 200 and 'openapi:' in spec
            assert record['valid']
        finally:
            process.terminate()
            process.wait(timeout=30)
            record['exit_code'] = process.returncode
            record['proc_absent'] = not pathlib.Path(f'/proc/{process.pid}').exists()
            write_json(folder / 'result.json', record)
    print(jar.stem, record['valid'], flush=True)
