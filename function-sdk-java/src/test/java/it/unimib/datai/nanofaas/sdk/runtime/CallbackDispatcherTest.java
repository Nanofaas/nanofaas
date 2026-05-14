package it.unimib.datai.nanofaas.sdk.runtime;

import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CallbackDispatcherTest {

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
    void submit_delegatesToCallbackClient() throws Exception {
        CallbackClient callbackClient = mock(CallbackClient.class);
        CountDownLatch delivered = new CountDownLatch(1);
        when(callbackClient.sendResult(anyString(), any(CallbackPayload.class), any())).thenAnswer(invocation -> {
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
        dispatcher = new CallbackDispatcher(callbackClient, executor);

        boolean accepted = dispatcher.submit("exec-1", CallbackPayload.success(TextNode.valueOf("ok")), "trace-1");

        assertTrue(accepted);
        assertTrue(delivered.await(2, TimeUnit.SECONDS));
        verify(callbackClient).sendResult(eq("exec-1"), any(CallbackPayload.class), eq("trace-1"));
    }

    @Test
    void submit_returnsFalseWhenQueueIsFull() throws Exception {
        CallbackClient callbackClient = mock(CallbackClient.class);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(callbackClient.sendResult(anyString(), any(CallbackPayload.class), any())).thenAnswer(invocation -> {
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
        dispatcher = new CallbackDispatcher(callbackClient, executor);

        assertTrue(dispatcher.submit("exec-1", CallbackPayload.success(TextNode.valueOf("one")), "trace-1"));
        assertTrue(running.await(2, TimeUnit.SECONDS));
        assertTrue(dispatcher.submit("exec-2", CallbackPayload.success(TextNode.valueOf("two")), "trace-2"));

        boolean accepted = dispatcher.submit("exec-3", CallbackPayload.success(TextNode.valueOf("three")), "trace-3");

        release.countDown();
        assertFalse(accepted);
    }

    @Test
    void submit_dispatchesMultipleCallbacksConcurrently() throws Exception {
        CallbackClient callbackClient = mock(CallbackClient.class);
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        AtomicInteger daemonWorkers = new AtomicInteger();
        when(callbackClient.sendResult(anyString(), any(CallbackPayload.class), any())).thenAnswer(invocation -> {
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
            if (Thread.currentThread().isDaemon()) {
                daemonWorkers.incrementAndGet();
            }
            started.countDown();
            try {
                assertTrue(release.await(2, TimeUnit.SECONDS));
            } finally {
                active.decrementAndGet();
            }
            return true;
        });
        dispatcher = new CallbackDispatcher(callbackClient, 2);

        assertTrue(dispatcher.submit("exec-1", CallbackPayload.success(TextNode.valueOf("one")), "trace-1"));
        assertTrue(dispatcher.submit("exec-2", CallbackPayload.success(TextNode.valueOf("two")), "trace-2"));

        assertTrue(started.await(1, TimeUnit.SECONDS));
        release.countDown();
        verify(callbackClient, timeout(2000).times(2)).sendResult(anyString(), any(CallbackPayload.class), any());
        assertEquals(maxActive.get(), daemonWorkers.get(),
                "Callback worker threads should be daemon threads");
        assertTrue(maxActive.get() >= 2);
    }

    @Test
    void shutdown_waitsForRunningCallbacksToFinish() throws Exception {
        CallbackClient callbackClient = mock(CallbackClient.class);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(callbackClient.sendResult(anyString(), any(CallbackPayload.class), any())).thenAnswer(invocation -> {
            running.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
            return true;
        });
        dispatcher = new CallbackDispatcher(callbackClient, 2);
        assertTrue(dispatcher.submit("exec-1", CallbackPayload.success(TextNode.valueOf("one")), "trace-1"));
        assertTrue(running.await(1, TimeUnit.SECONDS));

        FutureTask<Void> shutdownTask = new FutureTask<>(() -> {
            dispatcher.shutdown();
            return null;
        });
        Thread shutdownThread = new Thread(shutdownTask);
        shutdownThread.start();

        Thread.sleep(100);
        assertFalse(shutdownTask.isDone());

        release.countDown();
        shutdownTask.get(2, TimeUnit.SECONDS);
        verify(callbackClient).sendResult(eq("exec-1"), any(CallbackPayload.class), eq("trace-1"));
    }
}
