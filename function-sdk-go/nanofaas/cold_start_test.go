package nanofaas

import (
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestColdStartTrackerMarksOnlyFirstInvocation(t *testing.T) {
	tracker := NewColdStartTracker(func() time.Time {
		return time.UnixMilli(1000)
	})

	if !tracker.FirstInvocation() {
		t.Fatal("expected first invocation to be cold")
	}
	if tracker.FirstInvocation() {
		t.Fatal("expected subsequent invocation to be warm")
	}
}

func TestColdStartTrackerAllowsOnlyOneConcurrentFirstInvocation(t *testing.T) {
	tracker := NewColdStartTracker(func() time.Time {
		return time.UnixMilli(1000)
	})

	const goroutines = 64
	start := make(chan struct{})
	var wg sync.WaitGroup
	var coldCount atomic.Int32
	for range goroutines {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			if tracker.FirstInvocation() {
				coldCount.Add(1)
			}
		}()
	}

	close(start)
	wg.Wait()

	if coldCount.Load() != 1 {
		t.Fatalf("expected exactly one cold start winner, got %d", coldCount.Load())
	}
}

func TestColdStartTrackerCapturesOnlyFirstConcurrentArrival(t *testing.T) {
	var tick atomic.Int64
	tracker := NewColdStartTracker(func() time.Time {
		return time.UnixMilli(tick.Add(1))
	})

	const goroutines = 64
	start := make(chan struct{})
	var wg sync.WaitGroup
	for range goroutines {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			tracker.MarkFirstRequestArrival()
		}()
	}

	close(start)
	wg.Wait()

	if got := tracker.InitDurationMs(); got != 1 {
		t.Fatalf("expected first arrival duration 1ms, got %d", got)
	}
}
