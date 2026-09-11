"""Small real-HTTP SDK process profiles; no throughput comparison or simulated runtime transport."""
import concurrent.futures
import hashlib
import http.server
import json
import os
import pathlib
import re
import socket
import subprocess
import sys
import threading
import time
from http_runner import request, encoded, write_json, proc_snapshot
from contract import REVISIONS


def account(rows):
    result=dict(offered=len(rows),admitted=0,refused=0,terminal_successes=0,terminal_errors=0,callback_acknowledgements=0)
    for row in rows:
        body=row['body']
        code=body.get('error',{}).get('code','') if isinstance(body,dict) and isinstance(body.get('error',{}),dict) else ''
        if row['status'] in [413,429,503] or code in ['RUNTIME_HANDLER_SATURATED','RUNTIME_CALLBACK_SATURATED','RUNTIME_STOPPING','RUNTIME_INPUT_TOO_LARGE']:
            result['refused']+=1
        else:
            result['admitted']+=1
            if row['callback'] and row['status'] in [200,202,204]: result['callback_acknowledgements']+=1
            elif 200<=row['status']<300 and not code: result['terminal_successes']+=1
            else: result['terminal_errors']+=1
    return result


def census(samples):
    points=[]
    for sample in samples:
        values={}
        def flatten(value,path):
            if isinstance(value,dict):
                for key,item in value.items(): flatten(item,path+'.'+key)
            elif isinstance(value,(int,float)) and not isinstance(value,bool): values[path]=value
        flatten(sample.get('owners',{}),'owners')
        for line in str(sample.get('metrics','')).splitlines():
            match=re.match(r'([a-zA-Z_:][a-zA-Z0-9_:]*(?:\{[^\n]*\})?)\s+([+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?)(?:\s.*)?$',line)
            if match: values['metrics.'+match[1]]=float(match[2])
        points.append(values)
    keys={key for point in points for key in point}
    return {key:dict(peak=max(point[key] for point in points if key in point),
                     drain=points[-1].get(key,{'status':'unavailable','reason':'absent from final snapshot'})) for key in sorted(keys)}


def profiles(work, language):
    here=pathlib.Path(__file__).resolve().parent
    root=here.parents[3]
    folder=work/'profiles'/('sdk-'+language)
    folder.mkdir(parents=True,exist_ok=False)
    commands=json.loads((work/'sdk-commands.json').read_text())
    base_env=dict(os.environ,PORT='19080',FUNCTION_NAME='p19-'+language,NANOFAAS_HANDLER_TIMEOUT='100',
                  NANOFAAS_MAX_CONCURRENT_HANDLERS='2',NANOFAAS_MAX_PENDING_CALLBACKS='2',
                  NANOFAAS_MAX_INPUT_BYTES='1048576',NANOFAAS_MAX_OUTPUT_BYTES='8388608',
                  NANOFAAS_MAX_CALLBACK_PAYLOAD_BYTES='8388608',NANOFAAS_MAX_PENDING_CALLBACK_BYTES='16777216',
                  NANOFAAS_BODY_READ_TIMEOUT='300',NANOFAAS_BODY_READ_TIMEOUT_MS='300',
                  NANOFAAS_CALLBACK_ATTEMPT_TIMEOUT='1500',NANOFAAS_CALLBACK_ATTEMPT_TIMEOUT_MS='1500',
                  NANOFAAS_CALLBACK_MAX_ATTEMPTS='1',NANOFAAS_SHUTDOWN_TIMEOUT='2000',NANOFAAS_SHUTDOWN_TIMEOUT_MS='2000',
                  PYTHONPATH=str(root/'sdks/python/src'))
    if language in ['java','java-lite','go']: base_env['CALLBACK_URL']='http://127.0.0.1:19083/callback'
    callbacks=[]
    callback_active=0
    callback_lock=threading.Lock()
    class Callback(http.server.BaseHTTPRequestHandler):
        def log_message(self,*args): pass
        def do_POST(self):
            nonlocal callback_active
            data=self.rfile.read(int(self.headers['Content-Length']))
            with callback_lock:
                callback_active+=1
                callbacks.append(dict(path=self.path,bytes=len(data),sha256=hashlib.sha256(data).hexdigest(),
                                      success=json.loads(data).get('success'),started_ns=time.monotonic_ns()))
            try:
                time.sleep(.8)
                self.send_response(204);self.end_headers()
            finally:
                with callback_lock: callback_active-=1
    backend=http.server.ThreadingHTTPServer(('127.0.0.1',19083),Callback)
    backend_thread=threading.Thread(target=backend.serve_forever,daemon=True);backend_thread.start()
    result=dict(schema='p19-sdk-profile-v1',revision=REVISIONS['B'],language=language,valid=False,
                scope='Actual runtime HTTP, repeated non-cooperative timeouts, callback saturation/bytes, slow reader/body, two fresh start/stop generations',
                environment={k:v for k,v in base_env.items() if k.startswith('NANOFAAS_') or k in ['PORT','FUNCTION_NAME','PYTHONPATH','CALLBACK_URL']},
                command=commands[language],generations=[],unavailable={
                    'independent_future_aliases':'No exhaustive alias or dominator census',
                    'socket_buffer_bytes':'No per-request kernel-buffer attribution',
                    'owner_fields_absent_from_snapshot':'unavailable, never zero',
                    'control_plane_live_keys_outcomes':'not_applicable: direct SDK process'},started_ns=time.monotonic_ns())
    child=None
    try:
        for generation in range(2):
            samples=[];rows=[];cases=[];stop=threading.Event()
            with (folder/('runtime-'+str(generation)+'.log')).open('wb') as log:
                child=subprocess.Popen(commands[language],env=base_env,cwd=root,stdout=log,stderr=subprocess.STDOUT)
                deadline=time.monotonic()+40
                while time.monotonic()<deadline:
                    if child.poll() is not None: raise RuntimeError('SDK exited during startup')
                    try:
                        if request(19080,'GET','/health')[0]==200: break
                    except OSError: pass
                    time.sleep(.1)
                else: raise TimeoutError('SDK startup deadline')
                identity=proc_snapshot(child.pid)
                def sample(phase):
                    value=dict(phase=phase,monotonic_ns=time.monotonic_ns(),process=proc_snapshot(child.pid))
                    metric_status,metric_body=request(19080,'GET','/metrics',headers={'Accept':'text/plain'})
                    value['metrics']=metric_body if metric_status==200 else ''
                    value['metrics_status']='observed' if metric_status==200 else 'unavailable: HTTP '+str(metric_status)
                    if language!='javascript':
                        value['owners']=request(19080 if language=='python' else 19082,'GET',
                                                '/p19/snapshot' if language=='python' else '/snapshot')[1]
                    else: value['owners']={'status':'unavailable','reason':'JS private closures; exported numeric gauges collected separately'}
                    with callback_lock: value['callback_backend_active']=callback_active
                    samples.append(value)
                def periodic():
                    while not stop.wait(.05):
                        try: sample('traffic')
                        except Exception as error: samples.append(dict(status='unavailable',reason=repr(error),monotonic_ns=time.monotonic_ns()))
                sampler=threading.Thread(target=periodic,daemon=True);sampler.start();sample('idle')
                def invoke(identifier,delay=0,size=0,callback=False,input_size=0):
                    payload={'input':dict(id=identifier,delayMs=delay,outputBytes=size,padding='x'*input_size)}
                    headers={'X-Execution-Id':identifier,'X-Dispatch-Attempt':'1'}
                    if callback: headers['X-Callback-Url']='http://127.0.0.1:19083/callback/'+identifier
                    started=time.monotonic_ns();status,body=request(19080,'POST','/invoke',payload,headers)
                    # Retain exact error/id projection and body digest, never repeat multi-MiB bodies in dossier.
                    wire=encoded(body);projection=body if not isinstance(body,dict) else {k:v for k,v in body.items() if k not in ['data','output']}
                    row=dict(id=identifier,status=status,body=projection,body_bytes=len(wire),body_sha256=hashlib.sha256(wire).hexdigest(),
                             callback=callback and language not in ['java','java-lite','go'],sent_ns=started,done_ns=time.monotonic_ns())
                    rows.append(row);return row
                with concurrent.futures.ThreadPoolExecutor(max_workers=4) as clients:
                    for repetition in range(3):
                        before=len(rows)
                        futures=[clients.submit(invoke,f'{generation}-timeout-{repetition}-{i}',600) for i in range(4)]
                        for f in futures:f.result(timeout=10)
                        time.sleep(.7);sample('timeout-drain')
                        cases.append(dict(name='timeout-'+str(repetition),accounting=account(rows[before:])))
                    before=len(rows);callback_before=len(callbacks)
                    futures=[clients.submit(invoke,f'{generation}-callback-{i}',0,262144,True) for i in range(4)]
                    for f in futures:f.result(timeout=10)
                    time.sleep(2);sample('callback-drain')
                    cases.append(dict(name='large-blocked-callbacks',accounting=account(rows[before:]),
                                      observed_callbacks=callbacks[callback_before:]))
                    invoke(f'{generation}-normal',0,1024)
                    invoke(f'{generation}-input-limit',input_size=1048577)
                    invoke(f'{generation}-output-limit',size=8388609)
                    for slow_body in [True,False]:
                        sock=socket.socket();sock.setsockopt(socket.SOL_SOCKET,socket.SO_RCVBUF,1024);sock.settimeout(3)
                        sock.connect(('127.0.0.1',19080))
                        body=encoded({'input':dict(id=f'{generation}-slow-reader',delayMs=0,outputBytes=4194304)})
                        length=4096 if slow_body else len(body)
                        sock.sendall((f'POST /invoke HTTP/1.1\r\nHost: localhost\r\nX-Execution-Id: {generation}-slow-{slow_body}\r\nContent-Type: application/json\r\nContent-Length: {length}\r\nConnection: close\r\n\r\n').encode()+(b'{' if slow_body else body))
                        sample('slow-client-start');time.sleep(.6);sample('slow-client-blocked')
                        headers=b''
                        while b'\r\n\r\n' not in headers and len(headers)<8192:
                            try: part=sock.recv(1)
                            except TimeoutError: break
                            if not part:break
                            headers+=part
                        status=int(headers.split(b' ')[1]) if headers.startswith(b'HTTP/') else None
                        sock.close();time.sleep(.5);sample('slow-client-drain')
                        refusal=status in [400,408,413,429,503]
                        cases.append(dict(name='slow-body' if slow_body else 'slow-reader',offered=1,http_status=status,
                                          admitted=int(not refusal) if status is not None else {'status':'unavailable','reason':'no HTTP headers before client deadline'},completed=0,refused=int(refusal) if status is not None else {'status':'unavailable','reason':'no HTTP headers'},
                                          client_aborted=int(not refusal),completion_scope='useful terminal wire successes; body deliberately unread',
                                          missing_wire_status=status is None))
                time.sleep(1);sample('final-drain');stop.set();sampler.join(timeout=5)
                child.terminate();child.wait(timeout=20)
                result['generations'].append(dict(generation=generation,process=identity,accounting=account(rows),cases=cases,
                        rows=rows,owner_census=census(samples),exit_code=child.returncode,process_absent=not pathlib.Path(f'/proc/{child.pid}').exists()))
                write_json(folder/('populations-'+str(generation)+'.json'),samples)
                assert result['generations'][-1]['process_absent']
                child=None
        result['valid']=True
    except BaseException as failure:
        result['failure']=repr(failure);raise
    finally:
        if child is not None:
            stop.set()
            if 'sampler' in locals(): sampler.join(timeout=5)
            if 'samples' in locals(): write_json(folder/'failure-populations.json',samples)
            if 'rows' in locals(): write_json(folder/'failure-requests.json',rows)
            child.terminate()
            try:child.wait(timeout=20)
            except subprocess.TimeoutExpired:child.kill();child.wait(timeout=5)
        backend.shutdown();backend.server_close();backend_thread.join(timeout=5)
        result.update(ended_ns=time.monotonic_ns(),callbacks=callbacks)
        write_json(folder/'result.json',result)


if __name__=='__main__':
    profiles(pathlib.Path(sys.argv[1]),sys.argv[2])
