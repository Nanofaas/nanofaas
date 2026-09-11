package nanofaas

import (
 "context"
 "encoding/json"
 "net/http"
 "os"
 "os/signal"
 "runtime"
 "strings"
 "syscall"
 "testing"
 "time"
)

// Run alone in a fresh test executable; the SDK owns its real HTTP server and callback workers.
func TestP19ServeProfile(t *testing.T) {
 rt:=NewRuntime()
 rt.Register("measured",func(ctx context.Context,req InvocationRequest)(any,error){
  input:=req.Input.(map[string]any)
  time.Sleep(time.Duration(input["delayMs"].(float64))*time.Millisecond)
  return map[string]any{"id":input["id"],"data":strings.Repeat("x",int(input["outputBytes"].(float64)))},nil
 })
 probe:=&http.Server{Addr:"127.0.0.1:19082",Handler:http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){
  l:=rt.limits.snapshot();c:=rt.callbackDispatcher.snapshot()
  json.NewEncoder(w).Encode(map[string]any{"pid":os.Getpid(),"goroutines":runtime.NumGoroutine(),
   "active_handlers":l.activeHandlers,"input_bytes":l.inputBytes,"output_bytes":l.outputBytes,
   "pending_callbacks":c.pendingCallbacks,"pending_callback_bytes":c.pendingCallbackBytes,
   "serialized_callback_bytes":c.serializedCallbackBytes,"callback_queue":len(rt.callbackDispatcher.jobs)})
 })}
 go probe.ListenAndServe()
 ctx,stop:=signal.NotifyContext(context.Background(),syscall.SIGTERM,os.Interrupt);defer stop()
 err:=rt.Start(ctx)
 if ctx.Err()==nil && err!=nil {t.Error(err)}
 probe.Close()
 if l:=rt.limits.snapshot();l.activeHandlers!=0 {t.Errorf("handler drain: %+v",l)}
 if c:=rt.callbackDispatcher.snapshot();c.pendingCallbacks!=0 {t.Errorf("callback drain: %+v",c)}
}
