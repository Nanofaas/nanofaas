package it.unimib.datai.nanofaas.sdk.lite.callback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CallbackClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer mockServer;

    @AfterEach
    void tearDown() {
        if (mockServer != null) {
            mockServer.stop(0);
        }
    }

    @Test
    void successfulCallback() throws IOException {
        AtomicInteger callCount = new AtomicInteger();
        AtomicReference<String> receivedTraceId = new AtomicReference<>();

        mockServer = HttpServer.create(new InetSocketAddress(0), 0);
        mockServer.createContext("/", exchange -> {
            callCount.incrementAndGet();
            receivedTraceId.set(exchange.getRequestHeaders().getFirst("X-Trace-Id"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        mockServer.start();

        String baseUrl = "http://localhost:" + mockServer.getAddress().getPort();
        CallbackClient client = new CallbackClient(objectMapper, baseUrl);

        boolean result = client.sendResult("exec-1", InvocationResult.success(Map.of("ok", true)), "trace-abc");
        assertTrue(result);
        assertEquals(1, callCount.get());
        assertEquals("trace-abc", receivedTraceId.get());
    }

    @Test
    void retryOnFailure() throws IOException {
        AtomicInteger callCount = new AtomicInteger();

        mockServer = HttpServer.create(new InetSocketAddress(0), 0);
        mockServer.createContext("/", exchange -> {
            int count = callCount.incrementAndGet();
            if (count < 3) {
                exchange.sendResponseHeaders(500, -1);
            } else {
                exchange.sendResponseHeaders(200, -1);
            }
            exchange.close();
        });
        mockServer.start();

        String baseUrl = "http://localhost:" + mockServer.getAddress().getPort();
        CallbackClient client = new CallbackClient(objectMapper, baseUrl);

        boolean result = client.sendResult("exec-2", InvocationResult.success("ok"), null);
        assertTrue(result);
        assertEquals(3, callCount.get());
    }

    @Test
    void nullCallbackUrlReturnsFalse() {
        CallbackClient client = new CallbackClient(objectMapper, null);
        boolean result = client.sendResult("exec-3", InvocationResult.success("ok"), null);
        assertFalse(result);
    }

    @Test
    void blankExecutionIdReturnsFalse() {
        CallbackClient client = new CallbackClient(objectMapper, "http://localhost:9999");
        boolean result = client.sendResult("", InvocationResult.success("ok"), null);
        assertFalse(result);
    }

    // Need import for Map
    private static final java.util.Map<String, Object> EMPTY_MAP = java.util.Map.of();
}
