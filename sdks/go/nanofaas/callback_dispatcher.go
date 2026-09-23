package nanofaas

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"sync"
	"sync/atomic"
)

// minimumTerminalCallbackPayloadBytes leaves enough room for every canonical
// runtime failure callback. A reservation smaller than this cannot uphold the
// contract that every admitted handler has a terminal callback outcome.
const minimumTerminalCallbackPayloadBytes int64 = 256

type callbackJob struct {
	executionID     string
	body            []byte
	result          InvocationResult
	traceID         string
	dispatchAttempt string
	reservation     *CallbackReservation
}

type CallbackDispatcher struct {
	client                  *CallbackClient
	jobs                    chan callbackJob
	wg                      sync.WaitGroup
	closed                  atomic.Bool
	once                    sync.Once
	ctx                     context.Context
	cancel                  context.CancelFunc
	capacityMu              sync.Mutex
	maxPendingCallbacks     int
	maxPendingCallbackBytes int64
	maxCallbackPayloadBytes int64
	pendingCallbacks        int
	pendingCallbackBytes    int64
	serializedCallbackBytes int64
	onFailure               func()
	changed                 chan struct{}
}

func NewCallbackDispatcher(client *CallbackClient, workerCount, queueSize int) *CallbackDispatcher {
	if workerCount < 1 {
		workerCount = 1
	}
	if queueSize < 1 {
		queueSize = 1
	}
	return newCallbackDispatcher(client, workerCount, queueSize, workerCount+queueSize,
		defaultMaxPendingCallbackBytes, defaultMaxCallbackPayloadBytes)
}

func newCallbackDispatcher(client *CallbackClient, workerCount, queueSize, maxPendingCallbacks int,
	maxPendingCallbackBytes, maxCallbackPayloadBytes int64) *CallbackDispatcher {
	workerCount = boundedInt(workerCount, 1, maximumConcurrentHandlers)
	queueSize = boundedInt(queueSize, 1, maximumPendingCallbacks)
	maxPendingCallbacks = boundedInt(maxPendingCallbacks, 1, maximumPendingCallbacks)
	maxPendingCallbackBytes = boundedInt64(maxPendingCallbackBytes, 1, maximumPendingCallbackBytes)
	maxCallbackPayloadBytes = boundedInt64(maxCallbackPayloadBytes, 1, maximumCallbackPayloadBytes)
	if maxCallbackPayloadBytes < 1 || maxCallbackPayloadBytes > maxPendingCallbackBytes {
		maxCallbackPayloadBytes = maxPendingCallbackBytes
	}
	d := &CallbackDispatcher{
		client: client, jobs: make(chan callbackJob, queueSize),
		maxPendingCallbacks:     maxPendingCallbacks,
		maxPendingCallbackBytes: maxPendingCallbackBytes,
		maxCallbackPayloadBytes: maxCallbackPayloadBytes,
		changed:                 make(chan struct{}),
	}
	d.ctx, d.cancel = context.WithCancel(context.Background())
	for range workerCount {
		d.wg.Add(1)
		go d.work()
	}
	return d
}

func (d *CallbackDispatcher) work() {
	defer d.wg.Done()
	for job := range d.jobs {
		d.deliver(job)
	}
}

// deliver sends one callback and always returns its reservation.
func (d *CallbackDispatcher) deliver(job callbackJob) {
	defer job.reservation.Release()
	if job.body == nil {
		job.body, _ = encodeJSONBounded(job.result, d.maxCallbackPayloadBytes)
	}
	if d.client.SendSerializedWithDispatchAttempt(d.ctx, job.executionID, job.body, job.traceID, job.dispatchAttempt) {
		return
	}
	slog.Warn("callback delivery exhausted", "execution_id", job.executionID)
	if d.onFailure != nil {
		d.onFailure()
	}
}

type CallbackReservation struct {
	dispatcher      *CallbackDispatcher
	released        atomic.Bool
	serializedBytes atomic.Int64
}

func (d *CallbackDispatcher) TryReserve() *CallbackReservation {
	if d == nil || d.closed.Load() {
		return nil
	}
	d.capacityMu.Lock()
	defer d.capacityMu.Unlock()
	if d.closed.Load() || d.pendingCallbacks >= d.maxPendingCallbacks ||
		d.maxCallbackPayloadBytes > d.maxPendingCallbackBytes-d.pendingCallbackBytes {
		return nil
	}
	d.pendingCallbacks++
	d.pendingCallbackBytes += d.maxCallbackPayloadBytes
	d.signalChangedLocked()
	return &CallbackReservation{dispatcher: d}
}

func (r *CallbackReservation) Release() {
	if r == nil || r.dispatcher == nil || !r.released.CompareAndSwap(false, true) {
		return
	}
	d := r.dispatcher
	d.capacityMu.Lock()
	d.pendingCallbacks--
	d.pendingCallbackBytes -= d.maxCallbackPayloadBytes
	d.serializedCallbackBytes -= r.serializedBytes.Load()
	d.signalChangedLocked()
	d.capacityMu.Unlock()
}

type callbackDispatcherSnapshot struct {
	pendingCallbacks        int
	pendingCallbackBytes    int64
	serializedCallbackBytes int64
}

func (d *CallbackDispatcher) snapshot() callbackDispatcherSnapshot {
	d.capacityMu.Lock()
	defer d.capacityMu.Unlock()
	return callbackDispatcherSnapshot{d.pendingCallbacks, d.pendingCallbackBytes, d.serializedCallbackBytes}
}

func (d *CallbackDispatcher) changedSignal() <-chan struct{} {
	d.capacityMu.Lock()
	defer d.capacityMu.Unlock()
	return d.changed
}

func (d *CallbackDispatcher) signalChangedLocked() {
	if d.changed == nil {
		d.changed = make(chan struct{})
		return
	}
	close(d.changed)
	d.changed = make(chan struct{})
}

func (d *CallbackDispatcher) Submit(ctx context.Context, executionID string, result InvocationResult, traceID string) bool {
	return d.SubmitWithDispatchAttempt(ctx, executionID, result, traceID, "")
}

func (d *CallbackDispatcher) SubmitWithDispatchAttempt(ctx context.Context, executionID string, result InvocationResult, traceID, dispatchAttempt string) bool {
	reservation := d.TryReserve()
	if reservation == nil {
		return false
	}
	err := d.SubmitReserved(ctx, reservation, executionID, result, traceID, dispatchAttempt)
	if err != nil {
		reservation.Release()
	}
	return err == nil
}

func (d *CallbackDispatcher) SubmitReserved(ctx context.Context, reservation *CallbackReservation,
	executionID string, result InvocationResult, traceID, dispatchAttempt string) (submitErr error) {
	if reservation == nil || reservation.dispatcher != d || reservation.released.Load() {
		return errors.New("invalid callback reservation")
	}
	body, err := encodeJSONBounded(result, d.maxCallbackPayloadBytes)
	if err != nil {
		return fmt.Errorf("%w: %w", errJSONSerialization, err)
	}
	if !reservation.serializedBytes.CompareAndSwap(0, int64(len(body))) {
		return errors.New("callback reservation already serialized")
	}
	d.capacityMu.Lock()
	d.serializedCallbackBytes += int64(len(body))
	d.signalChangedLocked()
	d.capacityMu.Unlock()
	defer func() {
		if submitErr == nil {
			return
		}
		bytes := reservation.serializedBytes.Swap(0)
		d.capacityMu.Lock()
		d.serializedCallbackBytes -= bytes
		d.signalChangedLocked()
		d.capacityMu.Unlock()
	}()

	// Evaluate caller code outside the ownership lock. Only the nonblocking
	// send and channel close share this lock; serialization and delivery do not.
	done := ctx.Done()
	d.capacityMu.Lock()
	defer d.capacityMu.Unlock()
	if d.closed.Load() {
		return errors.New("callback dispatcher closed")
	}
	select {
	case d.jobs <- callbackJob{executionID: executionID, body: body, traceID: traceID, dispatchAttempt: dispatchAttempt, reservation: reservation}:
		return nil
	case <-done:
		return ctx.Err()
	default:
		return errors.New("callback queue saturated")
	}
}

func (d *CallbackDispatcher) Shutdown(ctx context.Context) error {
	d.once.Do(func() {
		d.capacityMu.Lock()
		d.closed.Store(true)
		close(d.jobs)
		d.capacityMu.Unlock()
		if d.cancel != nil {
			d.cancel()
		}
	})
	done := make(chan struct{})
	go func() {
		d.wg.Wait()
		close(done)
	}()
	select {
	case <-done:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}
