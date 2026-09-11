package it.unimib.datai.nanofaas.sdk.lite.callback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallbackClientInterruptionTest {
    @Test
    void interruptionDuringSendIsPreservedAndNeverRetried() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            attempts.incrementAndGet();
            entered.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            CallbackClient client = new CallbackClient(HttpClient.newHttpClient(), new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(5), 3,
                    new int[]{0, 0});
            AtomicBoolean delivered = new AtomicBoolean(true);
            AtomicBoolean interrupted = new AtomicBoolean();
            Thread sender = Thread.ofPlatform().start(() -> {
                delivered.set(client.sendSerializedResult("execution", "{}".getBytes(), null, null));
                interrupted.set(Thread.currentThread().isInterrupted());
            });

            assertTrue(entered.await(1, TimeUnit.SECONDS));
            sender.interrupt();
            sender.join(1_000);

            assertFalse(sender.isAlive());
            assertFalse(delivered.get());
            assertTrue(interrupted.get());
            assertEquals(1, attempts.get(), "an interrupted send must not enter the retry loop");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void interruptionDuringRetryDelayIsPreservedAndStopsRetries() throws Exception {
        CountDownLatch firstAttempt = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            attempts.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
            firstAttempt.countDown();
        });
        server.start();
        try {
            CallbackClient client = new CallbackClient(HttpClient.newHttpClient(), new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(1), 3,
                    new int[]{10_000, 10_000});
            AtomicBoolean interrupted = new AtomicBoolean();
            Thread sender = Thread.ofPlatform().start(() -> {
                client.sendSerializedResult("execution", "{}".getBytes(), null, null);
                interrupted.set(Thread.currentThread().isInterrupted());
            });

            assertTrue(firstAttempt.await(1, TimeUnit.SECONDS));
            sender.interrupt();
            sender.join(1_000);

            assertFalse(sender.isAlive());
            assertTrue(interrupted.get());
            assertEquals(1, attempts.get());
        } finally {
            server.stop(0);
        }
    }
}
