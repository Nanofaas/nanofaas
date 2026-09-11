package nanofaas

import (
	"sync"
	"sync/atomic"
	"time"
)

type nowFunc func() time.Time

type ColdStartTracker struct {
	now                 nowFunc
	containerStartMs    int64
	firstInvocationDone atomic.Bool
	firstRequestArrival atomic.Int64
	firstRequestOnce    sync.Once
}

func NewColdStartTracker(now nowFunc) *ColdStartTracker {
	if now == nil {
		now = time.Now
	}
	start := now().UnixMilli()
	tracker := &ColdStartTracker{now: now, containerStartMs: start}
	tracker.firstRequestArrival.Store(-1)
	return tracker
}

func (c *ColdStartTracker) FirstInvocation() bool {
	return c.firstInvocationDone.CompareAndSwap(false, true)
}

func (c *ColdStartTracker) MarkFirstRequestArrival() {
	c.firstRequestOnce.Do(func() {
		c.firstRequestArrival.Store(c.now().UnixMilli())
	})
}

func (c *ColdStartTracker) InitDurationMs() int64 {
	arrival := c.firstRequestArrival.Load()
	if arrival < 0 {
		return -1
	}
	return arrival - c.containerStartMs
}
