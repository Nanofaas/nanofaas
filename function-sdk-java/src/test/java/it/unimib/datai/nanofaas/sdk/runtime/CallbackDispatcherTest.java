package it.unimib.datai.nanofaas.sdk.runtime;

import it.unimib.datai.nanofaas.common.model.InvocationResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class CallbackDispatcherTest {

    private ThreadPoolExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void submit_delegatesToCallbackClient() throws Exception {
        CallbackClient callbackClient = mock(CallbackClient.class);
        CountDownLatch delivered = new CountDownLatch(1);
        when(callbackClient.sendResult(anyString(), any(), any())).thenAnswer(invocation -> {
            delivered.countDown();
            return true;
        });
        executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),
                new ThreadPoolExecutor.AbortPolicy());
        CallbackDispatcher dispatcher = new CallbackDispatcher(callbackClient, executor);

        boolean accepted = dispatcher.submit("exec-1", InvocationResult.success("ok"), "trace-1");

        assertTrue(accepted);
        assertTrue(delivered.await(2, TimeUnit.SECONDS));
        verify(callbackClient).sendResult(eq("exec-1"), any(InvocationResult.class), eq("trace-1"));
    }

    @Test
    void submit_returnsFalseWhenQueueIsFull() throws Exception {
        CallbackClient callbackClient = mock(CallbackClient.class);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(callbackClient.sendResult(anyString(), any(), any())).thenAnswer(invocation -> {
            running.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
            return true;
        });
        executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),
                new ThreadPoolExecutor.AbortPolicy());
        CallbackDispatcher dispatcher = new CallbackDispatcher(callbackClient, executor);

        assertTrue(dispatcher.submit("exec-1", InvocationResult.success("one"), "trace-1"));
        assertTrue(running.await(2, TimeUnit.SECONDS));
        assertTrue(dispatcher.submit("exec-2", InvocationResult.success("two"), "trace-2"));

        boolean accepted = dispatcher.submit("exec-3", InvocationResult.success("three"), "trace-3");

        release.countDown();
        assertFalse(accepted);
    }
}
