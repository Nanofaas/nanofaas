"""Bounded real-clock managed-provider and proxy HTTP profiles on existing ownership seams."""
import concurrent.futures
import http.server
import json
import pathlib
import socket
import subprocess
import sys
import threading
import time
import urllib.parse
from http_runner import request, encoded, write_json, proc_snapshot
from sdk_profiles import account, census
from contract import REVISIONS


def profile(work,kind):
    folder=work/'profiles'/kind;folder.mkdir(parents=True,exist_ok=False)
    command=json.loads((work/'owner-commands.json').read_text())[kind]
    state=dict(active=0,attempts=0,output_bytes=0,by_destination={},ids=[])
    lock=threading.Lock();servers=[];server_threads=[]
    class Backend(http.server.BaseHTTPRequestHandler):
        def log_message(self,*args):pass
        def do_GET(self):self.send_response(200);self.end_headers();self.wfile.write(b'UP')
        def do_POST(self):
            payload=json.loads(self.rfile.read(int(self.headers['Content-Length'])))['input']
            with lock:
                state['active']+=1;state['attempts']+=1;state['ids'].append(payload['id'])
                destination=str(self.server.server_port);state['by_destination'][destination]=state['by_destination'].get(destination,0)+1
            data=b''
            try:
                time.sleep(payload.get('delayMs',0)/1000)
                data=encoded(dict(id=payload['id'],data='x'*payload.get('outputBytes',0)))
                with lock:state['output_bytes']+=len(data)
                self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(data)));self.end_headers()
                self.wfile.write(data)
            except (BrokenPipeError,ConnectionResetError):pass
            finally:
                with lock:state['active']-=1;state['output_bytes']-=len(data)
    result=dict(schema='p19-owner-http-v1',kind=kind,revision=REVISIONS['B'],command=command,valid=False,
                started_ns=time.monotonic_ns(),cases=[],unavailable={
                    'independent_subscriber_aliases':'No exhaustive heap alias census; active exchanges/buffers/physical provider work sampled',
                    'SDK_owners':'not_applicable: deterministic HTTP backend, SDK processes measured separately',
                    'external_infrastructure':'not_applicable: loopback provider fault seam and real proxy; not a Kubernetes E2E claim'})
    samples=[];rows=[];child=None;stop=threading.Event();sampler=None
    try:
        for port in [19083,19085,19086]:
            server=http.server.ThreadingHTTPServer(('127.0.0.1',port),Backend);servers.append(server)
            thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start();server_threads.append(thread)
        with (folder/'runtime.log').open('wb') as log:
            child=subprocess.Popen(command,stdout=log,stderr=subprocess.STDOUT)
            deadline=time.monotonic()+45
            while time.monotonic()<deadline:
                if child.poll() is not None:raise RuntimeError('owner fixture exited at startup')
                try:
                    if request(19082,'GET','/snapshot')[0]==200:break
                except OSError:pass
                time.sleep(.1)
            else:raise TimeoutError('owner startup')
            result['process']=proc_snapshot(child.pid)
            def sample(phase):
                status,owners=request(19082,'GET','/snapshot');assert status==200
                point=dict(phase=phase,monotonic_ns=time.monotonic_ns(),owners=owners)
                if kind=='managed':point['metrics']=request(19081,'GET','/actuator/prometheus')[1]
                with lock:point['backend']=json.loads(json.dumps(state))
                samples.append(point);return point
            def periodic():
                while not stop.wait(.05):
                    try:sample('traffic')
                    except Exception as error:samples.append(dict(status='unavailable',reason=repr(error),monotonic_ns=time.monotonic_ns()))
            sampler=threading.Thread(target=periodic,daemon=True);sampler.start();sample('idle')
            def invoke(port,path,identifier,delay=0,size=0,key=None):
                started=time.monotonic_ns()
                status,body=request(port,'POST',path,{'input':dict(id=identifier,delayMs=delay,outputBytes=size)}, {'Idempotency-Key':key} if key else {})
                projection=body if not isinstance(body,dict) else {k:v for k,v in body.items() if k not in ['data','output']}
                row=dict(id=identifier,status=status,body=projection,callback=False,sent_ns=started,done_ns=time.monotonic_ns())
                rows.append(row);return row
            def drained(name):
                deadline=time.monotonic()+6
                while time.monotonic()<deadline:
                    point=sample(name+'-drain')
                    if point['backend']['active']==0 and (kind!='proxy' or point['owners']['in_flight']==0):break
                    time.sleep(.05)
                else:raise AssertionError('physical drain deadline: '+name)
                return point
            if kind=='managed':
                for name in ['blocked','healthy']:
                    status,spec=request(19080,'POST','/v1/functions',dict(name=name,image='fixture',executionMode='DEPLOYMENT',
                        concurrency=16,queueSize=16,maxRetries=0,timeoutMs=3000,
                        scalingConfig=dict(strategy='INTERNAL',minReplicas=0,maxReplicas=1)))
                    assert status==201,(status,spec)
                request(19084,'GET','/block');request(19084,'GET','/invalidate')
                with concurrent.futures.ThreadPoolExecutor(max_workers=8) as clients:
                    blocked=[clients.submit(invoke,19080,'/v1/functions/blocked:invoke','blocked-'+str(i)) for i in range(6)]
                    time.sleep(.15);sample('shared-wakeup-blocked')
                    for i in range(4):
                        request(19080,'GET','/v1/functions')
                        healthy=invoke(19080,'/v1/functions/healthy:invoke','healthy-'+str(i));assert healthy['status']==200,healthy
                        request(19084,'GET','/invalidate');sample('invalidate-'+str(i));time.sleep(.1)
                    for future in blocked:future.result(timeout=8)
                before_release=sample('logical-drain-provider-still-blocked')
                assert before_release['owners']['provider']['active_reads']>0
                status,_=request(19080,'DELETE','/v1/functions/blocked');assert 200<=status<300,status
                request(19084,'GET','/release')
                deadline=time.monotonic()+5
                while sample('provider-release')['owners']['provider']['active_reads'] and time.monotonic()<deadline:time.sleep(.05)
                assert sample('provider-drain')['owners']['provider']['active_reads']==0
                assert request(19080,'GET','/v1/functions/blocked')[0]==404
                result['cases'].append(dict(name='blocked-provider-shared-wakeup-invalidate-remove',accounting=account(rows),
                    invalidations=5,late_result_did_not_resurrect=True,physical_drain=drained('T6')))
                start=len(rows)
                for i in range(12):
                    name='churn-'+str(i);port=[19083,19085,19086][i%3]
                    status,_=request(19080,'POST','/v1/functions',dict(name=name,image='fixture',executionMode='EXTERNAL',
                        endpointUrl=f'http://127.0.0.1:{port}/',concurrency=2,timeoutMs=2000,maxRetries=0));assert status==201
                    first=invoke(19080,'/v1/functions/'+name+':invoke','churn-'+str(i),key='one')
                    replay=invoke(19080,'/v1/functions/'+name+':invoke','churn-'+str(i),key='one')
                    assert first['status']==replay['status']==200
                    assert first['body']['executionId']==replay['body']['executionId']
                    rows.pop() # Replay is observed below, not counted as newly offered useful work.
                    request(19080,'DELETE','/v1/functions/'+name);sample('destination-'+str(i));drained(name)
                request(19080,'DELETE','/v1/functions/healthy')
                time.sleep(4);point=sample('post-policy')
                deadline=time.monotonic()+10
                while point['owners']['owner_fields']['dispatchConnectionPool.pools.count'] and time.monotonic()<deadline:
                    time.sleep(.1);point=sample('destination-policy-drain')
                assert point['owners']['owner_fields']['dispatchConnectionPool.pools.count']==0,point
                for name in ['live','keys','outcomes','execution_reservations','canonical_input_bytes','physical_input_copy_bytes','waiters']:
                    assert point['owners']['populations'][name]['value']==0,(name,point)
                result['cases'].append(dict(name='multi-destination-churn',accounting=account(rows[start:]),replay_requests=12,
                                          unique_useful_successes=12,destinations=3,physical_drain=drained('churn')))
            else:
                endpoint=urllib.parse.urlparse(sample('proxy-ready')['owners']['endpoint'])
                with concurrent.futures.ThreadPoolExecutor(max_workers=4) as clients:
                    futures=[clients.submit(invoke,endpoint.port,'/invoke','saturation-'+str(i),600) for i in range(4)]
                    for f in futures:f.result(timeout=6)
                result['cases'].append(dict(name='proxy-saturation',accounting=account(rows),drain=drained('saturation')))
                start=len(rows)
                for i in range(6):
                    request(19082,'GET','/rotate');assert invoke(endpoint.port,'/invoke','destination-'+str(i))['status']==200
                    drained('destination-'+str(i))
                result['cases'].append(dict(name='proxy-destinations',accounting=account(rows[start:]),destinations=3))
                invoke(endpoint.port,'/invoke','output-limit',size=8388609);drained('output-limit')
                for slow_body in [True,False]:
                    sock=socket.socket();sock.setsockopt(socket.SOL_SOCKET,socket.SO_RCVBUF,1024);sock.settimeout(3)
                    sock.connect(('127.0.0.1',endpoint.port));body=encoded({'input':dict(id='slow-client',delayMs=0,outputBytes=4194304)})
                    sock.sendall((f'POST /invoke HTTP/1.1\r\nHost: localhost\r\nContent-Length: {4096 if slow_body else len(body)}\r\nConnection: close\r\n\r\n').encode()+(b'{' if slow_body else body))
                    time.sleep(.1);sample('slow-client-active');time.sleep(.7)
                    headers=b''
                    while b'\r\n\r\n' not in headers and len(headers)<8192:
                        part=sock.recv(1)
                        if not part:break
                        headers+=part
                    status=int(headers.split(b' ')[1]) if headers.startswith(b'HTTP/') else None
                    sock.close();refusal=status in [400,408,413,429,503]
                    result['cases'].append(dict(name='slow-body' if slow_body else 'slow-reader',offered=1,http_status=status,
                        admitted=int(not refusal) if status is not None else {'status':'unavailable','reason':'connection closed without response headers'},completed=0,
                        refused=int(refusal) if status is not None else {'status':'unavailable','reason':'connection closed without response headers'},
                        client_aborted=int(not refusal),missing_wire_status=status is None,
                        completion_scope='useful terminal wire successes; body deliberately unread',drain=drained('slow-client')))
                request(19082,'GET','/close');point=sample('proxy-closed')
                assert point['owners']['in_flight']==point['owners']['buffered_bytes']==0
            stop.set();sampler.join(timeout=5)
            result.update(accounting=account(rows),rows=rows,owner_census=census(samples),selected_owners=samples[-1].get('owners'),valid=True)
    except BaseException as failure:result['failure']=repr(failure);raise
    finally:
        stop.set()
        if sampler:sampler.join(timeout=5)
        if child:
            child.terminate()
            try:child.wait(timeout=20)
            except subprocess.TimeoutExpired:child.kill();child.wait(timeout=5)
            result.update(exit_code=child.returncode,process_absent=not pathlib.Path(f'/proc/{child.pid}').exists())
        for server in servers:server.shutdown();server.server_close()
        for thread in server_threads:thread.join(timeout=5)
        result.update(ended_ns=time.monotonic_ns(),backend=state)
        write_json(folder/'result.json',result);write_json(folder/'populations.json',samples)


if __name__=='__main__':profile(pathlib.Path(sys.argv[1]),sys.argv[2])
