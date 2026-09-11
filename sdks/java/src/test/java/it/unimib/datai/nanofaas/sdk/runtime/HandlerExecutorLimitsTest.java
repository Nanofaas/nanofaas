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
        assertThrows(RuntimeStoppingException.class, () ->
                executor.execute(_ -> "bad", new InvocationRequest(null, null)));
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
                    try { release.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                    return "ok";
                },
                        new InvocationRequest(null, null));
            } catch (Exception ignored) { }
        });
        assertTrue(entered.await(1, TimeUnit.SECONDS));

        assertThrows(HandlerSaturatedException.class, () ->
                executor.execute(_ -> "second", new InvocationRequest(null, null)));
        release.countDown();
        first.join(1_000);
        assertEquals("again", executor.execute(_ -> "again", new InvocationRequest(null, null)));
        executor.shutdown();
    }
}
