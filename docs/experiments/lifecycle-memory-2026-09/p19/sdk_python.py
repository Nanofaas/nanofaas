"""Actual uvicorn/SDK launcher with a deliberately non-cooperative synchronous handler."""
import os
import threading
import time
from dataclasses import asdict
from nanofaas.sdk.decorator import nanofaas_function
from nanofaas.runtime import app as runtime
import uvicorn


@nanofaas_function
def measured(input):
    time.sleep(input['delayMs']/1000)
    return {'id': input['id'], 'data': 'x'*input['outputBytes']}


@runtime.app.get('/p19/snapshot')
def snapshot():
    work=runtime._runtime_work
    result=asdict(work.snapshot())
    result.update(pid=os.getpid(), monotonic_ns=time.monotonic_ns(), threads=threading.active_count())
    for name,value in vars(work).items():
        if isinstance(value,(set,list,dict)): result[name+'.count']=len(value)
        elif isinstance(value,(int,bool)): result[name]=value
    return result


if __name__=='__main__':
    uvicorn.run(runtime.app,host='127.0.0.1',port=19080,log_level='warning')
