package it.unimib.datai.nanofaas.sdk.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CallbackDispatcherLimitsTest {
    @Test
    void legacySubmitAlsoReservesTheFiniteCallbackByteBudget() throws Exception {
        CallbackClient callbackClient = mock(CallbackClient.class);
        when(callbackClient.serializeBounded(any(), anyInt())).thenReturn(new byte[] {'{', '}'});
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(callbackClient.sendSerializedResult(anyString(), any(byte[].class), any(), any())).thenAnswer(_ -> {
            entered.countDown();
            release.await();
            return true;
        });
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.AbortPolicy());
        dispatcher = new CallbackDispatcher(callbackClient, executor, null, 2, 128, 64);

        assertTrue(dispatcher.submit("exec", CallbackPayload.error("E", "m"), null, null));
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertEquals(64, dispatcher.pendingCallbackBytes());

        release.countDown();
        org.awaitility.Awaitility.await().atMost(1, TimeUnit.SECONDS)
                .untilAsserted(() -> assertEquals(0, dispatcher.pendingCallbackBytes()));
    }

    @Test
    void rejectsAnOversizedSerializedCallbackBeforeQueueing() {
        org.springframework.web.client.RestClient restClient = mock(org.springframework.web.client.RestClient.class);
        CallbackClient callbackClient = new CallbackClient(restClient,
                mock(RuntimeSettings.class), tools.jackson.databind.json.JsonMapper.builder().build());
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.AbortPolicy());
        dispatcher = new CallbackDispatcher(callbackClient, executor, null, 1, 64, 64);

        assertEquals(CallbackDispatcher.SubmitResult.PAYLOAD_TOO_LARGE,
                dispatcher.submit(dispatcher.reserveInvocation(), "exec",
                        CallbackPayload.error("E", "large"), null, null));

        assertEquals(0, dispatcher.pendingCallbackCount());
        assertEquals(0, dispatcher.pendingCallbackBytes());
        assertTrue(executor.getQueue().isEmpty());
        verifyNoInteractions(restClient);
    }

    @Test
    void boundedStopReleasesRunningAndQueuedReservations() throws Exception {
        CallbackClient callbackClient = mock(CallbackClient.class);
        when(callbackClient.serializeBounded(any(), anyInt())).thenReturn(new byte[] {'{', '}'});
        var entered = new java.util.concurrent.CountDownLatch(1);
        when(callbackClient.sendSerializedResult(anyString(), any(byte[].class), any(), any())).thenAnswer(_ -> {
            entered.countDown();
            try {
                new java.util.concurrent.CountDownLatch(1).await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return false;
        });
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.AbortPolicy());
        dispatcher = new CallbackDispatcher(callbackClient, executor, null, 2, 128, 64,
                java.time.Duration.ofMillis(20));
        assertEquals(CallbackDispatcher.SubmitResult.ACCEPTED,
                dispatcher.submit(dispatcher.tryReserve(64), "one", CallbackPayload.error("E", "m"), null, null));
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertEquals(CallbackDispatcher.SubmitResult.ACCEPTED,
                dispatcher.submit(dispatcher.tryReserve(64), "two", CallbackPayload.error("E", "m"), null, null));

        dispatcher.shutdown();

        org.awaitility.Awaitility.await().atMost(1, TimeUnit.SECONDS)
                .untilAsserted(() -> assertEquals(0, dispatcher.pendingCallbackCount()));
        assertEquals(0, dispatcher.pendingCallbackBytes());
    }


    private ThreadPoolExecutor executor;
    private CallbackDispatcher dispatcher;

    @AfterEach
    void tearDown() {
        if (dispatcher != null) {
            dispatcher.shutdown();
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void reservesCallbackCountAndBytesBeforeHandlerAndReleasesThemExactlyOnce() {
        executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.AbortPolicy());
        dispatcher = new CallbackDispatcher(mock(CallbackClient.class), executor, null, 1, 64);

        CallbackDispatcher.CallbackReservation reservation = dispatcher.tryReserve(64);

        assertNotNull(reservation);
        assertEquals(1, dispatcher.pendingCallbackCount());
        assertEquals(64, dispatcher.pendingCallbackBytes());
        assertNull(dispatcher.tryReserve(1), "count and byte admission must fail before handler start");

        reservation.close();
        reservation.close();

        assertEquals(0, dispatcher.pendingCallbackCount());
        assertEquals(0, dispatcher.pendingCallbackBytes());
    }

    @Test
    void submittedReservationIsOwnedUntilPhysicalCallbackExit() throws Exception {
        var callbackClient = mock(CallbackClient.class);
        when(callbackClient.serializeBounded(any(), anyInt())).thenReturn(new byte[] {'{', '}'});
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(callbackClient.sendSerializedResult(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(byte[].class), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenAnswer(_ -> {
                    entered.countDown();
                    release.await();
                    return true;
                });
        executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.AbortPolicy());
        dispatcher = new CallbackDispatcher(callbackClient, executor, null, 1, 64);
        var reservation = dispatcher.tryReserve(64);

        assertEquals(CallbackDispatcher.SubmitResult.ACCEPTED,
                dispatcher.submit(reservation, "exec", CallbackPayload.error("E", "m"), null, null));
        org.junit.jupiter.api.Assertions.assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertEquals(1, dispatcher.pendingCallbackCount());

        release.countDown();
        org.awaitility.Awaitility.await().atMost(1, TimeUnit.SECONDS)
                .untilAsserted(() -> assertEquals(0, dispatcher.pendingCallbackCount()));
        assertEquals(0, dispatcher.pendingCallbackBytes());
    }
}
