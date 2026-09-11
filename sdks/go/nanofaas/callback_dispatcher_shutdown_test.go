package nanofaas

import (
	"context"
	"testing"
	"time"
)

// Gate evaluation of the send select without adding a production test hook.
type submissionGateContext struct {
	context.Context
	ready   chan struct{}
	proceed chan struct{}
}

func (c submissionGateContext) Done() <-chan struct{} {
	close(c.ready)
	<-c.proceed
	return nil
}

func TestSubmitReservedShutdownInterleaving(t *testing.T) {
	for range 100 {
		d := NewCallbackDispatcher(NewCallbackClient(""), 1, 1)
		r := d.TryReserve()
		gate := submissionGateContext{context.Background(), make(chan struct{}), make(chan struct{})}
		submitted := make(chan error, 1)
		go func() { submitted <- d.SubmitReserved(gate, r, "race", Success("ok"), "", "") }()
		select {
		case <-gate.ready:
		case <-time.After(time.Second):
			t.Fatal("submission did not reach gate")
		}
		stopped := make(chan error, 1)
		go func() {
			<-gate.proceed
			ctx, cancel := context.WithTimeout(context.Background(), time.Second)
			defer cancel()
			stopped <- d.Shutdown(ctx)
		}()
		close(gate.proceed)
		select {
		case err := <-submitted:
			if err != nil {
				r.Release()
			}
		case <-time.After(time.Second):
			t.Fatal("submission deadlocked")
		}
		if err := <-stopped; err != nil {
			t.Fatal(err)
		}
		if got := d.snapshot(); got != (callbackDispatcherSnapshot{}) {
			t.Fatalf("retained callback after shutdown: %+v", got)
		}
		if d.TryReserve() != nil {
			t.Fatal("shutdown admitted a reservation")
		}
	}
}
