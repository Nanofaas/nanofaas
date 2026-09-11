package nanofaas

import (
	"context"
	"sync"
)

type runtimeState int

const (
	runtimeStateIdle runtimeState = iota
	runtimeStateRunning
	runtimeStateStopping
)

type runtimeLifecycle struct {
	mu      sync.Mutex
	state   runtimeState
	changed chan struct{}
}

func newRuntimeLifecycle() *runtimeLifecycle {
	return &runtimeLifecycle{state: runtimeStateIdle, changed: make(chan struct{})}
}

func (l *runtimeLifecycle) set(state runtimeState) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.state = state
	close(l.changed)
	l.changed = make(chan struct{})
}

func (l *runtimeLifecycle) wait(ctx context.Context, expected runtimeState) error {
	for {
		l.mu.Lock()
		if l.state == expected {
			l.mu.Unlock()
			return nil
		}
		changed := l.changed
		l.mu.Unlock()
		select {
		case <-changed:
		case <-ctx.Done():
			return ctx.Err()
		}
	}
}

func (r *Runtime) waitForState(ctx context.Context, expected runtimeState) error {
	return r.lifecycle.wait(ctx, expected)
}
