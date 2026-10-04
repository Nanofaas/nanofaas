package it.unimib.datai.nanofaas.sdk.lite.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import it.unimib.datai.nanofaas.sdk.lite.callback.CallbackClient;
import it.unimib.datai.nanofaas.sdk.lite.metrics.RuntimeMetrics;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class InvokeHandlerTcpBodyDeadlineTest {
    @ParameterizedTest
    @CsvSource({"false, empty", "false, partial", "false, complete", "true, empty", "true, partial", "true, complete"})
    @Timeout(15)
    void partialTcpUploadTimesOutWithoutStartingHandlerOrRetainingCapacity(boolean chunked, String body) throws Exception {
        assertPartialTcpUpload(chunked, body, new ObjectMapper().readTree(java.nio.file.Files.readAllBytes(
                SharedFailureWireCorpusTest.find("sdks/runtime-contract/failure-wire-corpus.json"))));
    }

    void assertPartialTcpUpload(boolean chunked, String body, com.fasterxml.jackson.databind.JsonNode corpus) throws Exception {
        long beganAt = System.nanoTime();
        ObjectMapper mapper = new ObjectMapper();
        var config = corpus.path("config");
        var expected = corpus.path("contractDefinitions").path("ingress-io-timeout");
        var counters = corpus.path("finalCounters");
        int bodyReadTimeoutMs = config.path("bodyReadTimeoutMs").asInt();
        int deadlineMs = config.path("deadlineMs").asInt();
        var limits = new RuntimeLimits(1, 1, 2048, 1024, 1024, 1024, bodyReadTimeoutMs, 100, 1, 1000);
        AtomicInteger started = new AtomicInteger(), completed = new AtomicInteger();
        ThreadPoolExecutor callbacks = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
        var handler = new InvokeHandler(request -> { started.incrementAndGet(); return request.input(); },
                new CallbackClient(mapper, null), new RuntimeMetrics("tcp-deadline"), mapper, "tcp-deadline", callbacks, 1000, limits);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var connections = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(connections);
        server.createContext("/invoke", exchange -> { try { handler.handle(exchange); } finally { completed.incrementAndGet(); } });
        server.start();
        try {
            for (int i = 1; i <= 3; i++) {
                try (Socket socket = new Socket("127.0.0.1", server.getAddress().getPort())) {
                    socket.setSoTimeout(deadlineMs);
                    String prefix = switch (body) {
                        case "empty" -> "";
                        case "complete" -> "{\"input\":7}";
                        default -> "{\"input\":";
                    };
                    String framing = chunked ? "Transfer-Encoding: chunked\r\n" : "Content-Length: 1024\r\n";
                    String partial = chunked && !prefix.isEmpty() ? Integer.toHexString(prefix.length()) + "\r\n" + prefix + "\r\n" : prefix;
                    socket.getOutputStream().write(("POST /invoke HTTP/1.1\r\nHost: localhost\r\nX-Execution-Id: stalled\r\n" + framing + "\r\n" + partial).getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                    var input = new java.io.BufferedInputStream(socket.getInputStream());
                    var header = new java.io.ByteArrayOutputStream();
                    while (!header.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
                        int b = input.read(); assertNotEquals(-1, b, "response headers missing"); header.write(b);
                    }
                    String text = header.toString(StandardCharsets.US_ASCII);
                    assertTrue(text.startsWith("HTTP/1.1 " + expected.path("httpStatus").asInt()), text);
                    assertTrue(text.toLowerCase(java.util.Locale.ROOT).contains("connection: close"), text);
                    int length = Integer.parseInt(text.lines().filter(line -> line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")).findFirst().orElseThrow().split(":", 2)[1].trim());
                    assertEquals(expected.path("errorCode").asText(), mapper.readTree(input.readNBytes(length)).path("error").path("code").asText());
                    assertEquals(-1, input.read(), "server must close even while upload remains incomplete");
                    int expectedCompleted = i;
                    org.awaitility.Awaitility.await().atMost(Duration.ofMillis(deadlineMs)).until(() -> completed.get() == expectedCompleted);
                    assertEquals(expected.path("handlerStarted").asBoolean(), started.get() > 0);
                    assertEquals(counters.path("pendingCallbacks").asInt(), limits.pendingCallbacks()); assertEquals(counters.path("pendingCallbackBytes").asLong(), limits.pendingCallbackBytes());
                    assertEquals(counters.path("activeHandlers").asInt(), limits.activeHandlers());
                }
            }
            try (var client = java.net.http.HttpClient.newHttpClient()) {
                var response = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/invoke"))
                        .timeout(Duration.ofSeconds(2)).header("X-Execution-Id", "healthy")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"input\":7}")).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode()); assertEquals("7", response.body()); assertEquals(1, started.get());
            }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - beganAt) < deadlineMs,
                    "TCP lifecycle exceeded the shared overall deadline");
        } finally {
            server.stop(0); handler.shutdown(Duration.ofSeconds(1)); callbacks.shutdownNow(); connections.close();
        }
    }
}
