import {pathToFileURL} from 'node:url';
const {createRuntime}=await import(pathToFileURL(process.argv[2]));
const runtime=createRuntime({port:19080,handlerTimeoutMs:100,maxConcurrentHandlers:2,
    callbackQueueSize:2,maxPendingCallbackBytes:16777216,maxCallbackPayloadBytes:8388608,
    maxInputBytes:1048576,maxOutputBytes:8388608,bodyReadTimeoutMs:300,
    callbackAttemptTimeoutMs:1500,callbackMaxAttempts:1,shutdownTimeoutMs:2000});
runtime.register('measured',async(_,req)=>{
    await new Promise(resolve=>setTimeout(resolve,req.input.delayMs));
    return {id:req.input.id,data:'x'.repeat(req.input.outputBytes)};
});
await runtime.start();
process.on('SIGTERM',async()=>{await runtime.stop();process.exitCode=0;});
