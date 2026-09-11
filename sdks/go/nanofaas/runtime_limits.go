package nanofaas

import (
	"context"
	"sync"
	"sync/atomic"
)

type runtimeLimitSnapshot struct {
	activeHandlers int
	inputBytes     int64
	outputBytes    int64
	accepting      bool
}

type runtimeLimits struct {
	mu             sync.Mutex
	maxHandlers    int
	activeHandlers int
	inputBytes     int64
	outputBytes    int64
	accepting      bool
	changed        chan struct{}
}

func newRuntimeLimits(settings RuntimeSettings) *runtimeLimits {
	settings = normalizedRuntimeSettings(settings)
	return &runtimeLimits{
		maxHandlers: settings.MaxConcurrentHandlers,
		accepting:   true,
		changed:     make(chan struct{}),
	}
}

func (l *runtimeLimits) tryReserveHandler() *handlerReservation {
	l.mu.Lock()
	defer l.mu.Unlock()
	if !l.accepting || l.activeHandlers >= l.maxHandlers {
		return nil
	}
	l.activeHandlers++
	l.signalLocked()
	return &handlerReservation{limits: l}
}

func (l *runtimeLimits) stopAdmission() {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.accepting = false
	l.signalLocked()
}

func (l *runtimeLimits) startAdmission() {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.accepting = true
	l.signalLocked()
}

func (l *runtimeLimits) snapshot() runtimeLimitSnapshot {
	l.mu.Lock()
	defer l.mu.Unlock()
	return runtimeLimitSnapshot{l.activeHandlers, l.inputBytes, l.outputBytes, l.accepting}
}

func (l *runtimeLimits) changedSignal() <-chan struct{} {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.changed
}

func (l *runtimeLimits) waitForHandlers(ctx context.Context) error {
	for {
		l.mu.Lock()
		if l.activeHandlers == 0 {
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

func (l *runtimeLimits) signalLocked() {
	close(l.changed)
	l.changed = make(chan struct{})
}

func (l *runtimeLimits) retainOutput(bytes int64) func() {
	l.mu.Lock()
	l.outputBytes += bytes
	l.signalLocked()
	l.mu.Unlock()
	var released atomic.Bool
	return func() {
		if !released.CompareAndSwap(false, true) {
			return
		}
		l.mu.Lock()
		l.outputBytes -= bytes
		l.signalLocked()
		l.mu.Unlock()
	}
}

type handlerReservation struct {
	limits     *runtimeLimits
	inputBytes atomic.Int64
	released   atomic.Bool
}

func (r *handlerReservation) retainInput(bytes int64) {
	if bytes <= 0 {
		return
	}
	r.inputBytes.Store(bytes)
	r.limits.mu.Lock()
	r.limits.inputBytes += bytes
	r.limits.signalLocked()
	r.limits.mu.Unlock()
}

func (r *handlerReservation) release() {
	if r == nil || !r.released.CompareAndSwap(false, true) {
		return
	}
	r.limits.mu.Lock()
	r.limits.activeHandlers--
	r.limits.inputBytes -= r.inputBytes.Load()
	r.limits.signalLocked()
	r.limits.mu.Unlock()
}
