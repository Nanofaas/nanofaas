package it.unimib.datai.nanofaas.modules.offload.oneshot;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static it.unimib.datai.nanofaas.modules.offload.oneshot.OneShotLocalClusterE2eTest.*;
import static org.assertj.core.api.Assertions.assertThat;

/** The same real-process HTTP contract is exercised against JVM and native artifacts. */
@Tag("one-shot-e2e")
class RuntimeHttpArtifactE2eTest {
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void waiterTimeoutPreservesExecutionAndMarkedTextIsDecoded() throws Exception {
        var harness = new OneShotLocalClusterE2eTest();
        var evidence = harness.root.resolve("platform/modules/offload/build/test-diagnostics");
        Files.createDirectories(evidence);
        harness.artifacts = Files.createTempDirectory(evidence, "nf-http-artifact-");
        harness.dockerHost = "unix:///var/run/docker.sock";
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var physicalCalls = new AtomicInteger();
        var endpoint = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        endpoint.setExecutor(executor);
        endpoint.createContext("/invoke", exchange -> {
            physicalCalls.incrementAndGet();
            entered.countDown();
            try {
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                exchange.getRequestBody().readAllBytes();
                var body = "\"hello\"".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                exchange.getResponseHeaders().set("X-NanoFaaS-Function-Status", "true");
                exchange.sendResponseHeaders(202, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        endpoint.start();
        try {
            var node = harness.start("http-contract", false, 0);
            json(request(node, "/v1/functions", "POST", Map.of("name", "marked",
                    "image", "external", "executionMode", "EXTERNAL", "runtimeMode", "HTTP",
                    "endpointUrl", "http://127.0.0.1:" + endpoint.getAddress().getPort() + "/invoke",
                    "timeoutMs", 10000, "concurrency", 1, "queueSize", 10, "maxRetries", 0), Map.of()), 201);
            var timedOut = json(request(node, "/v1/functions/marked:invoke", "POST", Map.of("input", "p"),
                    Map.of("Idempotency-Key", "shared-http", "X-Timeout-Ms", "20")), 408);
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(timedOut.path("status").asString()).isEqualTo("timeout");
            assertThat(timedOut.has("waiterTimedOut")).isFalse();
            String id = timedOut.path("executionId").asString();
            assertThat(id).isNotBlank();
            release.countDown();
            var answer = json(request(node, "/v1/functions/marked:invoke", "POST", Map.of("input", "p"),
                    Map.of("Idempotency-Key", "shared-http", "X-Timeout-Ms", "5000")), 202);
            assertThat(answer.path("executionId").asString()).isEqualTo(id);
            assertThat(answer.path("output").asString()).isEqualTo("hello");
            assertThat(answer.path("statusCode").asInt()).isEqualTo(202);
            var replay = json(request(node, "/v1/functions/marked:invoke", "POST", Map.of("input", "p"),
                    Map.of("Idempotency-Key", "shared-http")), 202);
            assertThat(replay.path("output").asString()).isEqualTo("hello");
            assertThat(physicalCalls.get()).isEqualTo(1);
            Files.writeString(harness.artifacts.resolve("http-contract.json"),
                    JSON.writeValueAsString(Map.of("timeout", timedOut, "completed", answer, "replay", replay)));
        } finally {
            release.countDown();
            for (var node : harness.nodes) {
                node.process.destroy();
                if (!node.process.waitFor(10, TimeUnit.SECONDS)) node.process.destroyForcibly();
            }
            endpoint.stop(0);
            executor.close();
        }
    }
}
