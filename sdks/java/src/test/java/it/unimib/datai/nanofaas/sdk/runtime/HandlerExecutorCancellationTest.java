package it.unimib.datai.nanofaas.sdk.runtime;

import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandlerExecutorCancellationTest {
    @Test
    void interruptRequestsHandlerCancellationButRetainsPermitUntilPhysicalExit() throws Exception {
        HandlerExecutor executor = new HandlerExecutor(2_000, 1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch requestCaughtCancellation = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean requestInterrupted = new AtomicBoolean();
        Thread request = Thread.ofPlatform().start(() -> {
            try {
                executor.execute(_ -> {
                    entered.countDown();
                    try { release.await(); }
                    catch (InterruptedException _) {
                        interrupted.countDown();
                        while (release.getCount() != 0) Thread.onSpinWait();
                    }
                    return "late";
                }, new InvocationRequest(null, null));
            } catch (InterruptedException _) {
                requestInterrupted.set(true);
                requestCaughtCancellation.countDown();
            } catch (Exception _) { /* only the interruption matters here */ }
        });
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            request.interrupt();
            assertTrue(interrupted.await(1, TimeUnit.SECONDS), "handler must receive cancellation");
            assertEquals(1, executor.activeHandlerCount(),
                    "timed-out physical work must retain handler ownership");
            var secondRequest = new InvocationRequest(null, null);
            assertThrows(HandlerSaturatedException.class, () ->
                    executor.execute(_ -> "second", secondRequest));
            assertTrue(requestCaughtCancellation.await(1, TimeUnit.SECONDS),
                    "request thread must reach its terminal cancellation transition");
            assertTrue(requestInterrupted.get());
        } finally {
            release.countDown();
            request.join(1_000);
            org.awaitility.Awaitility.await().atMost(1, TimeUnit.SECONDS)
                    .untilAsserted(() -> assertEquals(0, executor.activeHandlerCount(),
                            "handler ownership must settle after physical exit"));
            executor.shutdown();
        }
    }
}
