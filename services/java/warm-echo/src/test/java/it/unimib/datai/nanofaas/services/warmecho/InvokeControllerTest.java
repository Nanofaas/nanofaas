package it.unimib.datai.nanofaas.services.warmecho;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "EXECUTION_ID=test-execution")
@AutoConfigureMockMvc
class InvokeControllerTest {
    private static final int CALLBACK_TIMEOUT_MS = 2_000;
    private static final int MAX_CALLBACK_BYTES = 2 * 1024 * 1024;
    private static final BlockingQueue<CallbackRequest> CALLBACKS = new LinkedBlockingQueue<>();
    private static final BlockingQueue<Throwable> ASYNC_FAILURES = new LinkedBlockingQueue<>();
    private static HttpServer callbackServer;
    private static ExecutorService callbackServerExecutor;
    private static Thread.UncaughtExceptionHandler previousUncaughtExceptionHandler;

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void registerCallbackUrl(DynamicPropertyRegistry registry) {
        startCallbackServer();
        registry.add("CALLBACK_URL", InvokeControllerTest::callbackBaseUrl);
    }

    @BeforeAll
    static void captureCallbackThreadFailures() {
        previousUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            if (thread.getName().startsWith("callback-dispatcher-")) {
                ASYNC_FAILURES.offer(failure);
            } else if (previousUncaughtExceptionHandler != null) {
                previousUncaughtExceptionHandler.uncaughtException(thread, failure);
            } else {
                failure.printStackTrace();
            }
        });
    }

    @AfterEach
    void assertNoAsyncFailureOrExtraCallback() {
        assertNoAsyncFailure();
        assertNull(CALLBACKS.poll(), "unexpected additional callback");
    }

    @AfterAll
    static void stopCallbackServer() throws InterruptedException {
        try {
            callbackServer.stop(0);
            callbackServerExecutor.shutdown();
            if (!callbackServerExecutor.awaitTermination(CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                callbackServerExecutor.shutdownNow();
                assertTrue(callbackServerExecutor.awaitTermination(
                        CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS), "callback server executor did not stop");
            }
            assertNoAsyncFailure();
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousUncaughtExceptionHandler);
        }
    }

    @Test
    void issue014_invokeContractReturnsInput() throws Exception {
        mockMvc.perform(post("/invoke")
                        .header("X-Dispatch-Attempt", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":{\"message\":\"hi\"}}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"message\":\"hi\"}"));

        assertSerializedCallback("test-execution", null, "1", "{\"message\":\"hi\"}");
    }

    @Test
    void invokeUsesExecutionIdFromHeader() throws Exception {
        mockMvc.perform(post("/invoke")
                        .header("X-Execution-Id", "header-exec-123")
                        .header("X-Dispatch-Attempt", "2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\": \"test\", \"metadata\": {}}"))
                .andExpect(status().isOk());

        assertSerializedCallback("header-exec-123", null, "2", "\"test\"");
    }

    @Test
    void invokeUsesEnvExecutionIdWhenHeaderNotProvided() throws Exception {
        mockMvc.perform(post("/invoke")
                        .header("X-Dispatch-Attempt", "3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\": \"test\", \"metadata\": {}}"))
                .andExpect(status().isOk());

        assertSerializedCallback("test-execution", null, "3", "\"test\"");
    }

    @Test
    void invokeHeaderTakesPrecedenceOverEnv() throws Exception {
        mockMvc.perform(post("/invoke")
                        .header("X-Execution-Id", "header-takes-precedence")
                        .header("X-Dispatch-Attempt", "4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\": \"test\", \"metadata\": {}}"))
                .andExpect(status().isOk());

        assertSerializedCallback("header-takes-precedence", null, "4", "\"test\"");
    }

    @Test
    void invokePropagatesTraceIdToCallback() throws Exception {
        mockMvc.perform(post("/invoke")
                        .header("X-Execution-Id", "exec-123")
                        .header("X-Trace-Id", "trace-456")
                        .header("X-Dispatch-Attempt", "5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\": \"test\", \"metadata\": {}}"))
                .andExpect(status().isOk());

        assertSerializedCallback("exec-123", "trace-456", "5", "\"test\"");
    }

    @Test
    void invokePropagatesNullTraceIdWhenHeaderNotProvided() throws Exception {
        mockMvc.perform(post("/invoke")
                        .header("X-Execution-Id", "exec-789")
                        .header("X-Dispatch-Attempt", "6")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\": \"test\", \"metadata\": {}}"))
                .andExpect(status().isOk());

        assertSerializedCallback("exec-789", null, "6", "\"test\"");
    }

    private void assertSerializedCallback(String executionId, String traceId, String dispatchAttempt,
                                          String expectedOutput) throws Exception {
        CallbackRequest callback = CALLBACKS.poll(CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertNotNull(callback, "callback delivery did not complete");
        assertEquals("POST", callback.method());
        assertEquals("/callbacks/" + executionId + ":complete", callback.path());
        assertEquals("application/json", callback.contentType());
        assertEquals(traceId, callback.traceId());
        assertEquals(dispatchAttempt, callback.dispatchAttempt());

        byte[] expectedBody = ("{\"success\":true,\"output\":" + expectedOutput + ",\"error\":null}")
                .getBytes(StandardCharsets.UTF_8);
        assertTrue(callback.body().length <= MAX_CALLBACK_BYTES, "callback body exceeded configured bound");
        assertArrayEquals(expectedBody, callback.body());
        assertNoAsyncFailure();
    }

    private static void startCallbackServer() {
        if (callbackServer != null) {
            return;
        }
        try {
            callbackServerExecutor = Executors.newSingleThreadExecutor();
            callbackServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            callbackServer.setExecutor(callbackServerExecutor);
            callbackServer.createContext("/callbacks/", InvokeControllerTest::receiveCallback);
            callbackServer.start();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static String callbackBaseUrl() {
        return "http://127.0.0.1:" + callbackServer.getAddress().getPort() + "/callbacks";
    }

    private static void receiveCallback(HttpExchange exchange) {
        CallbackRequest callback = null;
        try {
            callback = new CallbackRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    exchange.getRequestHeaders().getFirst("X-Trace-Id"),
                    exchange.getRequestHeaders().getFirst("X-Dispatch-Attempt"),
                    exchange.getRequestBody().readAllBytes());
            exchange.sendResponseHeaders(204, -1);
        } catch (Throwable failure) {
            ASYNC_FAILURES.offer(failure);
        } finally {
            exchange.close();
            if (callback != null) {
                CALLBACKS.offer(callback);
            }
        }
    }

    private static void assertNoAsyncFailure() {
        Throwable failure = ASYNC_FAILURES.poll();
        if (failure != null) {
            throw new AssertionError("uncaught asynchronous callback failure", failure);
        }
    }

    private record CallbackRequest(String method, String path, String contentType,
                                   String traceId, String dispatchAttempt, byte[] body) { }
}
