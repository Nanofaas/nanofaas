package it.unimib.datai.nanofaas.sdk.lite.callback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallbackClientDeadlineTest {
    @Test
    void boundedAttemptsEachHaveTheConfiguredDeadline() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            attempts.incrementAndGet();
            try { release.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        try { // NOSONAR (java:S2093): HttpServer is not AutoCloseable; teardown order matters
            CallbackClient client = new CallbackClient(HttpClient.newHttpClient(), new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofMillis(40), 3,
                    new int[]{0, 0});
            long started = System.nanoTime();

            assertFalse(client.sendSerializedResult("execution", "{}".getBytes(), null, null));

            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertEquals(3, attempts.get());
            assertTrue(elapsedMs < 500, "attempt deadlines must bound total callback retention");
        } finally {
            release.countDown();
            server.stop(0);
        }
    }
}
