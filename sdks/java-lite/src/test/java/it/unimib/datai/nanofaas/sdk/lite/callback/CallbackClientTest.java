package it.unimib.datai.nanofaas.sdk.lite.callback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
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

    @Test
    void callbackUrlAlwaysUsesAuthoritativeExecutionId() throws IOException {
        AtomicReference<String> path = new AtomicReference<>();
        mockServer = HttpServer.create(new InetSocketAddress(0), 0);
        mockServer.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        mockServer.start();

        String baseUrl = "http://localhost:" + mockServer.getAddress().getPort()
                + "/v1/internal/executions/placeholder:complete/";
        CallbackClient client = new CallbackClient(objectMapper, baseUrl);

        assertTrue(client.sendResult("exec-authoritative", InvocationResult.success("ok"), null));
        assertEquals("/v1/internal/executions/exec-authoritative:complete", path.get());
    }

    @Test
    void permanent4xxIsNotRetried() throws IOException {
        AtomicInteger callCount = new AtomicInteger();
        mockServer = HttpServer.create(new InetSocketAddress(0), 0);
        mockServer.createContext("/", exchange -> {
            callCount.incrementAndGet();
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
        });
        mockServer.start();

        CallbackClient client = new CallbackClient(
                objectMapper, "http://localhost:" + mockServer.getAddress().getPort());

        assertFalse(client.sendResult("exec-400", InvocationResult.success("ok"), null));
        assertEquals(1, callCount.get());
    }

    @Test
    void serializationFailureIsNotRetried() {
        AtomicInteger attempts = new AtomicInteger();
        ObjectMapper failingMapper = new ObjectMapper() {
            @Override
            public byte[] writeValueAsBytes(Object value) throws JsonProcessingException {
                attempts.incrementAndGet();
                throw JsonMappingException.fromUnexpectedIOE(new IOException("boom"));
            }
        };
        CallbackClient client = new CallbackClient(failingMapper, "http://localhost:1");

        assertFalse(client.sendResult("exec-json", InvocationResult.success("ok"), null));
        assertEquals(1, attempts.get());
    }
}
