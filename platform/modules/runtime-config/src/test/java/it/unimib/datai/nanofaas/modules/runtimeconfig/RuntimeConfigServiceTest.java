package it.unimib.datai.nanofaas.modules.runtimeconfig;

import it.unimib.datai.nanofaas.controlplane.config.SyncQueueRuntimeDefaults;
import it.unimib.datai.nanofaas.controlplane.service.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeConfigServiceTest {

    private RuntimeConfigService service;

    @BeforeEach
    void setUp() {
        RateLimiter rateLimiter = new RateLimiter();
        rateLimiter.setMaxPerSecond(1000);
        SyncQueueRuntimeDefaults defaults = new SyncQueueRuntimeDefaults(
                true, true, Duration.ofSeconds(5), Duration.ofSeconds(2), 2
        );
        service = new RuntimeConfigService(rateLimiter, defaults);
    }

    @Test
    void initialSnapshotSeededFromProperties() {
        RuntimeConfigSnapshot snapshot = service.getSnapshot();
        assertEquals(0, snapshot.revision());
        assertEquals(1000, snapshot.rateMaxPerSecond());
        assertTrue(snapshot.syncQueueEnabled());
        assertTrue(snapshot.syncQueueAdmissionEnabled());
        assertEquals(Duration.ofSeconds(5), snapshot.syncQueueMaxEstimatedWait());
        assertEquals(Duration.ofSeconds(2), snapshot.syncQueueMaxQueueWait());
        assertEquals(2, snapshot.syncQueueRetryAfterSeconds());
    }

    @Test
    void updateIncrementsRevision() {
        RuntimeConfigPatch patch = new RuntimeConfigPatch(500, null, null, null, null, null);
        RuntimeConfigSnapshot updated = service.update(0, patch);
        assertEquals(1, updated.revision());
        assertEquals(500, updated.rateMaxPerSecond());
        // unchanged fields preserved
        assertTrue(updated.syncQueueEnabled());
    }

    @Test
    void updateRejectsRevisionMismatch() {
        RuntimeConfigPatch patch = new RuntimeConfigPatch(500, null, null, null, null, null);
        RevisionMismatchException ex = assertThrows(
                RevisionMismatchException.class,
                () -> service.update(99, patch)
        );
        assertEquals(99, ex.getExpected());
        assertEquals(0, ex.getActual());
    }

    @Test
    void patchMergesPartialFields() {
        service.update(0, new RuntimeConfigPatch(null, null, false, null, null, null));
        RuntimeConfigSnapshot s = service.getSnapshot();
        assertEquals(1000, s.rateMaxPerSecond()); // unchanged
        assertFalse(s.syncQueueAdmissionEnabled()); // changed
    }

    @Test
    void concurrentUpdatesOnlyOneSucceeds() throws Exception {
        int threads = 10;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger mismatches = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int rate = 100 + i;
            new Thread(() -> {
                try {
                    start.await();
                    service.update(0, new RuntimeConfigPatch(rate, null, null, null, null, null));
                    successes.incrementAndGet();
                } catch (RevisionMismatchException e) {
                    mismatches.incrementAndGet();
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            }).start();
        }

        start.countDown();
        done.await();

        assertEquals(1, successes.get());
        assertEquals(threads - 1, mismatches.get());
        assertEquals(1, service.getSnapshot().revision());
    }

    @Test
    void restoreRevertsSnapshot() {
        RuntimeConfigSnapshot original = service.getSnapshot();
        service.update(0, new RuntimeConfigPatch(500, null, null, null, null, null));
        assertEquals(1, service.getSnapshot().revision());

        service.restore(original);
        assertEquals(0, service.getSnapshot().revision());
        assertEquals(1000, service.getSnapshot().rateMaxPerSecond());
    }

    @Test
    void patchAppliesAllFieldsWhenAllProvided() {
        RuntimeConfigPatch fullPatch = new RuntimeConfigPatch(
                2000, false, false, Duration.ofSeconds(10), Duration.ofSeconds(5), 3
        );
        RuntimeConfigSnapshot updated = service.update(0, fullPatch);
        assertEquals(2000, updated.rateMaxPerSecond());
        assertFalse(updated.syncQueueEnabled());
        assertFalse(updated.syncQueueAdmissionEnabled());
        assertEquals(Duration.ofSeconds(10), updated.syncQueueMaxEstimatedWait());
        assertEquals(Duration.ofSeconds(5), updated.syncQueueMaxQueueWait());
        assertEquals(3, updated.syncQueueRetryAfterSeconds());
    }
}
