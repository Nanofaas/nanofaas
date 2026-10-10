#!/usr/bin/env python3
"""Smoke GraalVM binaries and prove real control-plane/function correlation."""
import argparse
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
BINARIES = {'control-plane':'platform/control-plane/build/native/nativeCompile/control-plane',
            'warm-echo':'services/java/warm-echo/build/native/nativeCompile/warm-echo',
            'word-stats-lite':'functions/java/word-stats-lite/build/native/nativeCompile/word-stats-lite'}


def free_port(fixed=0):
    with socket.socket() as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(('127.0.0.1', fixed))
        return sock.getsockname()[1]


def request(port, path, body=None, headers=None):
    data = None if body is None else json.dumps(body).encode()
    return urllib.request.urlopen(urllib.request.Request(f'http://127.0.0.1:{port}{path}', data=data,
        headers={'Content-Type':'application/json', **(headers or {})}), timeout=4)


def ready(process, port, path):
    deadline=time.monotonic()+30
    while time.monotonic()<deadline:
        if process.poll() is not None: raise AssertionError(f'Runtime exited {process.returncode}')
        try:
            request(port,path)
            return
        except OSError: time.sleep(.1)
    raise AssertionError('Runtime readiness timed out')


def stop(process):
    process.terminate()
    try: process.wait(timeout=10)
    except subprocess.TimeoutExpired: process.kill(); process.wait()


def events(path):
    records=[]
    for line in path.read_text().splitlines():
        if line.startswith('{'):
            record=json.loads(line)
            assert record['schemaVersion']==1 and record['timestamp'].endswith('Z'),record
            assert record['service'] and record['logger'],record
            records.append(record)
    return records


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args(); args.output.mkdir(parents=True,exist_ok=True)
    receipt=[]
    with tempfile.TemporaryDirectory(prefix='nf-native-logs-') as directory:
        for name, relative in BINARIES.items():
            for mode in ['json','text']:
                port=free_port(8080 if name=='word-stats-lite' else 0)
                output=args.output/f'{name}-{mode}.log'
                env=dict(os.environ,NANOFAAS_LOG_FORMAT=mode,SERVER_PORT=str(port),
                         SPRING_MAIN_BANNER_MODE='off',NANOFAAS_REGISTRY_PATH=directory+'/functions.json')
                if name=='control-plane':env['MANAGEMENT_SERVER_PORT']='0'
                with output.open('w') as stream:
                    process=subprocess.Popen([str(ROOT/relative)],env=env,stdout=stream,stderr=subprocess.STDOUT)
                    try:
                        ready(process,port,'/v1/functions' if name=='control-plane' else '/health')
                        if name!='control-plane':
                            assert request(port,'/invoke',{'input':{'text':'hello logging'}},
                                           {'X-Execution-Id':'native-exec','X-Trace-Id':'native-trace'}).status==200
                        time.sleep(.2)
                    finally:stop(process)
                records=events(output)
                if mode=='json':
                    assert records,name
                    if name=='word-stats-lite':
                        assert any(e.get('executionId')=='native-exec' and e.get('traceId')=='native-trace' for e in records)
                else:assert not records,name
                receipt.append({'binary':name,'format':mode,'jsonEvents':len(records),'invocationVerified':name!='control-plane'})
        # Both real binaries, an actual external function registration and gateway invocation.
        cp_port=free_port(); fn_port=free_port(8080)
        processes=[]; streams=[]
        try:
            for name,number in [('control-plane',cp_port),('word-stats-lite',fn_port)]:
                stream=(args.output/f'correlation-{name}.log').open('w');streams.append(stream)
                env=dict(os.environ,NANOFAAS_LOG_FORMAT='json',SERVER_PORT=str(number),
                         SPRING_MAIN_BANNER_MODE='off',NANOFAAS_REGISTRY_PATH=directory+'/correlation.json')
                argv=[str(ROOT/BINARIES[name])]
                if name=='control-plane':
                    env['MANAGEMENT_SERVER_PORT']='0'
                    argv+=['--logging.level.it.unimib.datai.nanofaas.controlplane.dispatch=DEBUG']
                process=subprocess.Popen(argv,env=env,stdout=stream,stderr=subprocess.STDOUT);processes.append(process)
                ready(process,number,'/v1/functions' if name=='control-plane' else '/health')
            request(cp_port,'/v1/functions',{'name':'logging-fixture','image':'fixture',
                    'executionMode':'EXTERNAL','endpointUrl':f'http://127.0.0.1:{fn_port}/invoke',
                    'timeoutMs':3000,'concurrency':1,'queueSize':10,'maxRetries':0})
            response=request(cp_port,'/v1/functions/logging-fixture:invoke',{'input':{'text':'real gateway invocation'}},
                             {'X-Trace-Id':'gateway-trace'})
            identity=response.headers['X-Execution-Id']; assert response.status==200 and identity
            time.sleep(.3)
        finally:
            for process in reversed(processes):stop(process)
            for stream in streams:stream.close()
        correlated={name:[e for e in events(args.output/f'correlation-{name}.log')
                          if e.get('executionId')==identity and e.get('traceId')=='gateway-trace']
                    for name in ['control-plane','word-stats-lite']}
        assert all(correlated.values()),correlated
        receipt.append({'correlatedExecutionId':identity,'traceId':'gateway-trace',
                        'originEvents':len(correlated['control-plane']), 'destinationEvents':len(correlated['word-stats-lite'])})
    (args.output/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
    print(json.dumps(receipt,indent=2))

if __name__=='__main__':main()
