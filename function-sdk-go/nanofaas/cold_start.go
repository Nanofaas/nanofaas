package nanofaas

import (
	"sync/atomic"
	"time"
)

type nowFunc func() time.Time

type ColdStartTracker struct {
	now                 nowFunc
	containerStartMs    int64
	firstInvocationDone atomic.Bool
	firstRequestArrival atomic.Int64
}

func NewColdStartTracker(now nowFunc) *ColdStartTracker {
	if now == nil {
		now = time.Now
	}
	start := now().UnixMilli()
	firstRequestArrival := atomic.Int64{}
	firstRequestArrival.Store(-1)
	return &ColdStartTracker{
		now:                 now,
		containerStartMs:    start,
		firstRequestArrival: firstRequestArrival,
	}
}

func (c *ColdStartTracker) FirstInvocation() bool {
	return c.firstInvocationDone.CompareAndSwap(false, true)
}

func (c *ColdStartTracker) MarkFirstRequestArrival() {
	c.firstRequestArrival.CompareAndSwap(-1, c.now().UnixMilli())
}

func (c *ColdStartTracker) InitDurationMs() int64 {
	arrival := c.firstRequestArrival.Load()
	if arrival < 0 {
		return -1
	}
	return arrival - c.containerStartMs
}
