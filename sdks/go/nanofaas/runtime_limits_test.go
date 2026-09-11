package nanofaas

import "testing"

func TestRuntimeLimitsKeepPhysicalHandlerPermitUntilRelease(t *testing.T) {
	limits := newRuntimeLimits(RuntimeSettings{MaxConcurrentHandlers: 1})
	first := limits.tryReserveHandler()
	if first == nil {
		t.Fatal("first handler reservation must succeed")
	}
	if second := limits.tryReserveHandler(); second != nil {
		t.Fatal("second handler reservation must be rejected while physical work remains")
	}
	first.release()
	third := limits.tryReserveHandler()
	if third == nil {
		t.Fatal("handler capacity must return after physical release")
	}
	third.release()
	if got := limits.snapshot().activeHandlers; got != 0 {
		t.Fatalf("active handler count did not drain: %d", got)
	}
}

func TestRuntimeLimitsRejectAdmissionWhileStoppingAndAllowRestart(t *testing.T) {
	limits := newRuntimeLimits(RuntimeSettings{MaxConcurrentHandlers: 1})
	limits.stopAdmission()
	if reservation := limits.tryReserveHandler(); reservation != nil {
		t.Fatal("stopping runtime admitted handler work")
	}
	limits.startAdmission()
	reservation := limits.tryReserveHandler()
	if reservation == nil {
		t.Fatal("restarted runtime did not admit handler work")
	}
	reservation.release()
}
