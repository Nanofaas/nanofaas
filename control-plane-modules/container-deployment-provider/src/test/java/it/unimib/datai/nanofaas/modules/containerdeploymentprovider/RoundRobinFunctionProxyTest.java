package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RoundRobinFunctionProxyTest {

    private HttpServer backendA;
    private HttpServer backendB;
    private RoundRobinFunctionProxy proxy;

    @AfterEach
    void tearDown() {
        if (proxy != null) {
            proxy.close();
        }
        if (backendA != null) {
            backendA.stop(0);
        }
        if (backendB != null) {
            backendB.stop(0);
        }
    }

    @Test
    void endpointUrl_isStableAndRoundRobinsAcrossBackends() throws Exception {
        backendA = backend("a");
        backendB = backend("b");
        proxy = new RoundRobinFunctionProxy("127.0.0.1");
        proxy.updateBackends(List.of(baseUrl(backendA), baseUrl(backendB)));

        HttpClient client = HttpClient.newHttpClient();
        String first = post(client, proxy.endpointUrl());
        String second = post(client, proxy.endpointUrl());
        String third = post(client, proxy.endpointUrl());

        assertThat(proxy.endpointUrl()).startsWith("http://127.0.0.1:");
        assertThat(List.of(first, second, third)).containsExactly("a", "b", "a");
    }

    private static HttpServer backend(String responseBody) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/invoke", exchange -> {
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(response);
            }
        });
        server.start();
        return server;
    }

    private static String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String post(HttpClient client, String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .POST(HttpRequest.BodyPublishers.ofString("{\"input\":\"ok\"}"))
                .header("Content-Type", "application/json")
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }
}
