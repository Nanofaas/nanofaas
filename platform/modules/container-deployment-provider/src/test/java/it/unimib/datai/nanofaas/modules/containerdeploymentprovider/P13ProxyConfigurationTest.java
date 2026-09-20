package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import it.unimib.datai.nanofaas.containerdeployment.ManagedFunctionProxy;
import it.unimib.datai.nanofaas.containerdeployment.ManagedFunctionProxyFactory;
import it.unimib.datai.nanofaas.containerdeployment.RoundRobinFunctionProxy;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class P13ProxyConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ContainerDeploymentProviderConfiguration.class)
            .withPropertyValues(
                    "nanofaas.container-local.runtime-adapter=podman",
                    "nanofaas.container-local.proxy.max-request-bytes=3",
                    "nanofaas.container-local.proxy.max-response-bytes=5",
                    "nanofaas.container-local.proxy.max-buffered-bytes=7",
                    "nanofaas.container-local.proxy.inbound-read-timeout=123ms",
                    "nanofaas.container-local.proxy.response-write-timeout=456ms");

    @Test
    void configuredLimitsReachFactoryCreatedProxy() {
        contextRunner.run(context -> {
            ContainerProxyProperties properties = context.getBean(ContainerProxyProperties.class);
            assertThat(properties.maxRequestBytes()).isEqualTo(3);
            assertThat(properties.maxResponseBytes()).isEqualTo(5);
            assertThat(properties.maxBufferedBytes()).isEqualTo(7);
            assertThat(properties.inboundReadTimeout()).isEqualTo(Duration.ofMillis(123));
            assertThat(properties.responseWriteTimeout()).isEqualTo(Duration.ofMillis(456));

            HttpServer backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            backend.createContext("/invoke", exchange -> {
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(new byte[0]);
                }
            });
            backend.start();
            try (ManagedFunctionProxy managed = context.getBean(ManagedFunctionProxyFactory.class).create("configured")) {
                RoundRobinFunctionProxy proxy = (RoundRobinFunctionProxy) managed;
                proxy.updateBackends(List.of("http://127.0.0.1:" + backend.getAddress().getPort()));
                HttpResponse<String> response = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create(proxy.endpointUrl()))
                                .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[4]))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

                assertThat(response.statusCode()).isEqualTo(413);
            } finally {
                backend.stop(0);
            }
        });
    }
}
