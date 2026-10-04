package it.unimib.datai.nanofaas.sdk.runtime;

import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.runtime.FunctionHandler;
import it.unimib.datai.nanofaas.common.runtime.HandlerResponse;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class SharedFailureWireCorpusTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final class Unserializable {
        public String getValue() { throw new IllegalStateException("encoding fault"); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"envelope-serialization-failure", "callback-http-rejected", "ingress-io-timeout", "callback-io-timeout"})
    void executesSharedFailureLifecycle(String name) throws Exception {
        JsonNode corpus = MAPPER.readTree(Files.readAllBytes(CorpusTestSupport.find("sdks/runtime-contract/failure-wire-corpus.json")));
        CorpusTestSupport.validate(corpus);
        JsonNode expected = corpus.path("contractDefinitions").path(name);
        JsonNode config = corpus.path("config");
        AtomicBoolean started = new AtomicBoolean();
        if (name.equals("ingress-io-timeout")) {
            CountDownLatch release = new CountDownLatch(1);
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/invoke") {
                @Override public ServletInputStream getInputStream() {
                    return new ServletInputStream() {
                        @Override public boolean isFinished() { return false; }
                        @Override public boolean isReady() { return true; }
                        @Override public void setReadListener(ReadListener listener) { }
                        @Override public int read() throws IOException {
                            try { release.await(); return -1; }
                            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException(ex); }
                        }
                        @Override public void close() { release.countDown(); }
                    };
                }
            };
            MockHttpServletResponse response = new MockHttpServletResponse();
            try {
                assertTimeoutPreemptively(Duration.ofMillis(config.path("deadlineMs").asLong()), () ->
                        new RuntimePayloadLimitFilter(1024, config.path("bodyReadTimeoutMs").asLong())
                                .doFilter(request, response, (_, _) -> started.set(true)));
                assertEquals(expected.path("httpStatus").asInt(), response.getStatus());
                assertEquals(expected.path("errorCode").asText(), MAPPER.readTree(response.getContentAsString()).path("error").path("code").asText());
                assertEquals(expected.path("handlerStarted").asBoolean(), started.get());
                assertEquals(0, release.getCount(), "physical body read must have been released");
            } finally { release.countDown(); }
            return;
        }
        try (MockWebServer server = new MockWebServer()) {
            // Warm the JDK transport before measuring its short callback deadline.
            server.enqueue(new MockResponse().setResponseCode(204));
            server.start();
            HttpClientConfig transport = new HttpClientConfig(config.path("callbackAttemptTimeoutMs").asLong());
            try (var http = transport.callbackHttpClient()) {
                http.send(java.net.http.HttpRequest.newBuilder(server.url("/warmup").uri()).GET().build(), java.net.http.HttpResponse.BodyHandlers.discarding());
                server.takeRequest();
                int attempts = expected.path("callbackAttempts").asInt();
                for (int i = 0; i < attempts; i++) {
                    MockResponse response = new MockResponse().setResponseCode(expected.path("callbackStatus").isNull() ? 204 : expected.path("callbackStatus").asInt());
                    if (name.equals("callback-io-timeout")) response.setSocketPolicy(SocketPolicy.NO_RESPONSE);
                    server.enqueue(response);
                }
                RuntimeSettings settings = new RuntimeSettings(null, null, server.url("/callbacks").toString(), "failure");
                CallbackClient client = new CallbackClient(transport.restClient(http), settings, MAPPER, 4096, config.path("callbackMaxAttempts").asInt()) {
                    @Override protected void sleepBeforeRetry(int attemptIndex) { }
                };
                CallbackDispatcher dispatcher = new CallbackDispatcher(client, 1);
                HandlerExecutor executor = new HandlerExecutor(1000, 1);
                FunctionHandler handler = _ -> {
                    started.set(true);
                    return name.equals("envelope-serialization-failure") ? new HandlerResponse(new Unserializable(), 201, Map.of(), null) : corpus.path("successOutput");
                };
                try {
                    InvokeController controller = new InvokeController(dispatcher, new HandlerRegistry(Map.of("failure", handler), settings),
                            new InvocationRuntimeContextResolver(settings), new ColdStartTracker(), executor, new JsonOutputNormalizer(MAPPER));
                    var response = controller.invoke(new InvocationRequest(null, null), config.path("executionId").asText(), config.path("traceId").asText(), config.path("dispatchAttempt").asText());
                    assertEquals(expected.path("httpStatus").asInt(), response.getStatusCode().value());
                    assertEquals(expected.path("handlerStarted").asBoolean(), started.get());
                    JsonNode body = MAPPER.valueToTree(response.getBody());
                    if (!expected.path("errorCode").isNull()) assertEquals(expected.path("errorCode").asText(), body.path("error").path("code").asText());
                    assertTimeoutPreemptively(Duration.ofMillis(config.path("deadlineMs").asLong()), dispatcher::shutdown);
                    assertEquals(corpus.path("finalCounters").path("pendingCallbacks").asInt(), dispatcher.pendingCallbackCount());
                    assertEquals(corpus.path("finalCounters").path("pendingCallbackBytes").asLong(), dispatcher.pendingCallbackBytes());
                    assertEquals(corpus.path("finalCounters").path("activeHandlers").asInt(), executor.activeHandlerCount());
                    assertEquals(attempts + 1, server.getRequestCount());
                    for (int i = 0; i < attempts; i++) {
                        var callback = server.takeRequest(1, TimeUnit.SECONDS);
                        assertNotNull(callback);
                        assertTrue(callback.getPath().contains(config.path("executionId").asText()));
                        assertEquals(config.path("dispatchAttempt").asText(), callback.getHeader("X-Dispatch-Attempt"));
                        assertEquals(config.path("traceId").asText(), callback.getHeader("X-Trace-Id"));
                        JsonNode payload = MAPPER.readTree(callback.getBody().readUtf8());
                        assertEquals(expected.path("errorCode").isNull(), payload.path("success").asBoolean());
                        if (expected.path("errorCode").isNull()) assertEquals(MAPPER.valueToTree(corpus.path("successOutput")), payload.path("output"));
                        else assertEquals(body.path("error"), payload.path("error"));
                    }
                } finally { dispatcher.shutdown(); executor.shutdown(); }
            }
        }
    }
}
