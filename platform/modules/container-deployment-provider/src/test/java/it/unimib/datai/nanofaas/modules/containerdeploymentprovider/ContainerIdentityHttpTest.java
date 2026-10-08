package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import com.sun.net.httpserver.HttpServer;
import it.unimib.datai.nanofaas.containerdeployment.*;
import it.unimib.datai.nanofaas.common.model.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerIdentityHttpTest {
    @ParameterizedTest
    @MethodSource("distinctNames")
    void distinctFunctionNamesDoNotReplaceEachOther(String first, String second) throws Exception {
        try (var adapter = new LoopbackAdapter();
             var provider = new ContainerLocalDeploymentProvider(adapter,
                     new ContainerLocalProperties("docker", "127.0.0.1", Duration.ofSeconds(1), Duration.ofMillis(10), null),
                     new EndpointProbe() {
                         public void awaitReady(String url, Duration timeout, Duration interval) { }
                         public boolean isReady(String url) { return true; }
                     }, new RoundRobinFunctionProxyFactory("127.0.0.1"));
             var client = HttpClient.newHttpClient()) {
            var a = provider.provision(spec(first));
            var b = provider.provision(spec(second));
            for (String endpoint : List.of(a.endpointUrl(), b.endpointUrl())) {
                for (int i = 0; i < 6; i++) {
                    var request = HttpRequest.newBuilder(URI.create(endpoint))
                            .timeout(Duration.ofSeconds(2)).POST(HttpRequest.BodyPublishers.ofString("{}" )).build();
                    assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
                }
            }
            assertThat(adapter.servers).hasSize(2);
        }
    }

    static Stream<Arguments> distinctNames() {
        return Stream.of(Arguments.of("Echo", "echo"), Arguments.of("foo_bar", "foo-bar"),
                Arguments.of("Écho", "écho"), Arguments.of("long".repeat(30) + "A", "long".repeat(30) + "B"));
    }

    private static FunctionSpec spec(String name) {
        return new FunctionSpec(name, "img", List.of(), Map.of(), null, 1000, 4, 100, 3, null,
                ExecutionMode.DEPLOYMENT, RuntimeMode.HTTP, null,
                new ScalingConfig(ScalingStrategy.INTERNAL, 1, 5, List.of()));
    }

    private static final class LoopbackAdapter implements ContainerRuntimeAdapter, AutoCloseable {
        private final Map<String, HttpServer> servers = new LinkedHashMap<>();
        public boolean isAvailable() { return true; }
        public void pullImage(String image) { }
        public ManagedContainer runContainer(ContainerInstanceSpec spec) {
            try {
                // Model Docker's old replacement behavior so a naming collision kills the first endpoint.
                var previous = servers.remove(spec.containerName());
                if (previous != null) previous.stop(0);
                var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/invoke", exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    byte[] body = "ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
                server.start();
                servers.put(spec.containerName(), server);
                return new ManagedContainer(spec.containerName(), 1,
                        "http://127.0.0.1:" + server.getAddress().getPort(), true);
            } catch (java.io.IOException error) {
                throw new IllegalStateException(error);
            }
        }
        public void removeContainer(String name) {
            var server = servers.remove(name);
            if (server != null) server.stop(0);
        }
        public List<ManagedContainer> listManagedContainers(String function) { return List.of(); }
        public void close() { servers.values().forEach(server -> server.stop(0)); }
    }
}
