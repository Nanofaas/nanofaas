"""Own and remove only the two uniquely named P19 smoke containers."""
import pathlib
import subprocess
import sys
import time
from http_runner import request, write_json

work = pathlib.Path(sys.argv[1])
for selection in (sys.argv[2:] or ['none', 'k8s']):
    folder = work / 'smoke' / ('native-' + selection)
    folder.mkdir(parents=True, exist_ok=False)
    image = 'nanofaas/control-plane:p19-6d083033-' + selection
    name = 'nanofaas-p19-smoke-' + selection + '-' + str(time.time_ns())
    command = ['docker', 'run', '-d', '--name', name, '--memory=512m', '--cpus=4',
               '-p', '127.0.0.1:18880:8080', '-p', '127.0.0.1:18881:8081', image]
    cid = subprocess.check_output(command, text=True).strip()
    record = dict(command=command, container_id=cid, valid=False)
    try:
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
        record['valid'] = status == 200 and 'openapi:' in spec
        assert record['valid']
        record['image_inspect'] = subprocess.check_output(['docker', 'image', 'inspect', image], text=True)
    finally:
        record['stop_exit'] = subprocess.run(['docker', 'stop', '--time', '20', cid], capture_output=True).returncode
        (folder / 'output.log').write_bytes(subprocess.check_output(['docker', 'logs', cid], stderr=subprocess.STDOUT))
        record['remove_exit'] = subprocess.run(['docker', 'rm', cid], capture_output=True).returncode
        write_json(folder / 'result.json', record)
    print('native-' + selection, record['valid'], flush=True)
