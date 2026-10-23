#!/usr/bin/env python3
"""Start real SDK HTTP runtimes, invoke handlers, retain canonical correlated logs."""
import argparse
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.request

ROOT=Path(__file__).resolve().parents[2]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--python',default='python3')
    parser.add_argument('--node',default='node')
    parser.add_argument('--cargo',default='cargo')
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    receipt=[]
    node_path=shutil.which(args.node) or args.node
    subprocess.run(['npm','run','build'],cwd=ROOT/'sdks/javascript',check=True,
                   env=dict(os.environ,PATH=str(Path(node_path).parent)+os.pathsep+os.environ['PATH']))
    with tempfile.TemporaryDirectory(prefix='nf-sdk-logs-') as directory:
        tmp=Path(directory)
        (tmp/'python_fixture.py').write_text('''from nanofaas.sdk.decorator import nanofaas_function
from nanofaas.sdk.context import get_logger
from nanofaas.runtime.app import app
@nanofaas_function
def handle(value):
    get_logger("fixture").info("SDK handler caffè\\nnext")
    return {"ok": True}
''')
        (tmp/'js_fixture.mjs').write_text('import {createRuntime} from '+json.dumps((ROOT/'sdks/javascript/dist/index.js').as_uri())+''';
const rt=createRuntime();
rt.register("fixture",(ctx,req)=>{ctx.logger.info("SDK handler caffè\\nnext");return {ok:true};});
await rt.start();
''')
        go=tmp/'go';go.mkdir()
        (go/'go.mod').write_text('module fixture\ngo 1.24.0\nrequire github.com/miciav/nanofaas/function-sdk-go v0.0.0\nreplace github.com/miciav/nanofaas/function-sdk-go => '+str(ROOT/'sdks/go')+'\n')
        (go/'main.go').write_text('''package main
import("context";"os";"github.com/miciav/nanofaas/function-sdk-go/nanofaas")
func main(){logger:=nanofaas.NewLogger(os.Stdout,"function-runtime");rt:=nanofaas.NewRuntime(nanofaas.WithLogger(logger));rt.Register("fixture",func(ctx context.Context,req nanofaas.InvocationRequest)(any,error){nanofaas.Logger(ctx,logger).Info("SDK handler caffè\\nnext");return map[string]bool{"ok":true},nil});if err:=rt.Start(context.Background());err!=nil{panic(err)}}
''')
        subprocess.run(['go','build','-buildvcs=false','-mod=mod','-o',str(tmp/'go-fixture'),'.'],cwd=go,check=True)
        rust=tmp/'rust';(rust/'src').mkdir(parents=True)
        (rust/'Cargo.toml').write_text('''[package]
name="logging-fixture"
version="0.0.0"
edition="2024"
[dependencies]
nanofaas-sdk={path='''+json.dumps(str(ROOT/'sdks/rust'))+'''}
tokio={version="1",features=["rt","macros"]}
serde_json="1"
tracing="0.1"
''')
        (rust/'src/main.rs').write_text('''use nanofaas::{Runtime,Context,BoxError};
#[tokio::main(flavor="current_thread")]
async fn main()->Result<(),BoxError>{nanofaas::logging::init_logging()?;Runtime::from_env().register("fixture",|_:Context,_:serde_json::Value|async{tracing::info!("SDK handler caffè\\nnext");Ok::<_,BoxError>(serde_json::json!({"ok":true}))}).start().await?;Ok(())}
''')
        shutil.copyfile(ROOT/'sdks/rust/Cargo.lock', rust/'Cargo.lock')
        target=os.environ.get('CARGO_TARGET_DIR',str(tmp/'target'))
        subprocess.run([args.cargo,'build','--offline','--manifest-path',str(rust/'Cargo.toml')],check=True,
                       env=dict(os.environ,CARGO_TARGET_DIR=target))
        for name,argv,cwd in [('python',[args.python,'-m','uvicorn','python_fixture:app','--host','127.0.0.1','--port','{port}','--no-access-log'],tmp),
                              ('javascript',[args.node,str(tmp/'js_fixture.mjs')],ROOT),
                              ('go',[str(tmp/'go-fixture')],ROOT),
                              ('rust',[str(Path(target)/'debug/logging-fixture')],ROOT)]:
            with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
            output=args.output/(name+'.log')
            env=dict(os.environ,NANOFAAS_LOG_FORMAT='json',PORT=str(port),
                     PYTHONPATH=str(tmp)+os.pathsep+str(ROOT/'sdks/python/src'),FUNCTION_HANDLER='fixture')
            with output.open('w') as stream:
                process=subprocess.Popen([arg.replace('{port}',str(port)) for arg in argv],cwd=cwd,env=env,
                                         stdout=stream,stderr=subprocess.STDOUT)
                try:
                    deadline=time.monotonic()+30
                    while time.monotonic()<deadline:
                        if process.poll() is not None:raise AssertionError(output.read_text())
                        try:urllib.request.urlopen(f'http://127.0.0.1:{port}/health',timeout=.5);break
                        except OSError:time.sleep(.1)
                    else:raise AssertionError(name+' readiness')
                    for suffix in ['one','two']:
                        req=urllib.request.Request(f'http://127.0.0.1:{port}/invoke',data=b'{"input":{}}',headers={
                            'Content-Type':'application/json','X-Execution-Id':name+'-'+suffix,'X-Trace-Id':'trace-'+suffix})
                        response=urllib.request.urlopen(req,timeout=4);assert response.status==200
                    time.sleep(.3)
                finally:
                    process.terminate()
                    try:process.wait(timeout=10)
                    except subprocess.TimeoutExpired:process.kill();process.wait()
            found=[]
            for line in output.read_text().splitlines():
                if not line.startswith('{'):continue
                event=json.loads(line)
                if event.get('message','').startswith('SDK handler'):
                    assert event['schemaVersion']==1 and event['service']=='function-runtime',event
                    found.append(event)
            assert {e['executionId'] for e in found}=={name+'-one',name+'-two'},(name,output.read_text())
            assert {e['traceId'] for e in found}=={'trace-one','trace-two'}
            receipt.append({'sdk':name,'invocations':2,'correlatedHandlerLogs':len(found)})
    (args.output/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
    print(json.dumps(receipt,indent=2))

if __name__=='__main__':main()
