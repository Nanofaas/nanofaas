package nanofaas

import (
	"context"
	"sync"
	"sync/atomic"
)

type callbackJob struct {
	executionID string
	result      InvocationResult
	traceID     string
}

type CallbackDispatcher struct {
	client *CallbackClient
	jobs   chan callbackJob
	wg     sync.WaitGroup
	closed atomic.Bool
	once   sync.Once
	ctx    context.Context
	cancel context.CancelFunc
}

func NewCallbackDispatcher(client *CallbackClient, workerCount, queueSize int) *CallbackDispatcher {
	if workerCount < 1 {
		workerCount = 1
	}
	if queueSize < 1 {
		queueSize = 1
	}
	d := &CallbackDispatcher{
		client: client,
		jobs:   make(chan callbackJob, queueSize),
	}
	d.ctx, d.cancel = context.WithCancel(context.Background())
	for range workerCount {
		d.wg.Add(1)
		go func() {
			defer d.wg.Done()
			for job := range d.jobs {
				d.client.SendResult(d.ctx, job.executionID, job.result, job.traceID)
			}
		}()
	}
	return d
}

func (d *CallbackDispatcher) Submit(ctx context.Context, executionID string, result InvocationResult, traceID string) bool {
	if d.closed.Load() {
		return false
	}

	defer func() {
		if recover() != nil {
			d.closed.Store(true)
		}
	}()

	select {
	case d.jobs <- callbackJob{executionID: executionID, result: result, traceID: traceID}:
		return true
	case <-ctx.Done():
		return false
	default:
		return false
	}
}

func (d *CallbackDispatcher) Shutdown(ctx context.Context) error {
	d.once.Do(func() {
		d.closed.Store(true)
		if d.cancel != nil {
			d.cancel()
		}
		close(d.jobs)
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
