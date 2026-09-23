package it.unimib.datai.nanofaas.sdk.runtime;

import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class HandlerExecutorLimitsTest {
    @Test
    void rejectsAdmissionAfterStopWithLifecycleSpecificSignal() {
        HandlerExecutor executor = new HandlerExecutor(1_000, 1);
        executor.shutdown();
        var request = new InvocationRequest(null, null);
        assertThrows(RuntimeStoppingException.class, () ->
                executor.execute(_ -> "bad", request));
    }

    @Test
    void rejectsASecondPhysicalHandlerAndReleasesCapacityOnRealExit() throws Exception {
        HandlerExecutor executor = new HandlerExecutor(2_000, 1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread first = Thread.ofPlatform().start(() -> {
            try {
                executor.execute(_ -> {
                    entered.countDown();
                    try { release.await(); } catch (InterruptedException _) { Thread.currentThread().interrupt(); }
                    return "ok";
                },
                        new InvocationRequest(null, null));
            } catch (Exception _) { /* this call only occupies the slot */ }
        });
        assertTrue(entered.await(1, TimeUnit.SECONDS));

        var request = new InvocationRequest(null, null);
        assertThrows(HandlerSaturatedException.class, () ->
                executor.execute(_ -> "second", request));
        release.countDown();
        first.join(1_000);
        assertEquals("again", executor.execute(_ -> "again", new InvocationRequest(null, null)));
        executor.shutdown();
    }
}
