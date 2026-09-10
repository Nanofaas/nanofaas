package it.unimib.datai.nanofaas.controlplane.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1: the connection pool is an explicit, bounded budget with an explicit lifecycle,
 * not whatever Reactor Netty's global defaults happen to be.
 */
class HttpClientPoolTest {

    @Test
    void theConfiguredConnectionBudgetReachesTheProvider() {
        HttpClientConfig config = new HttpClientConfig();
        HttpClientProperties properties = new HttpClientProperties(
                null, null, null, 42, null, null, null, null, null, null, null);

        try (DispatchConnectionPool owner = new DispatchConnectionPool(properties, new SimpleMeterRegistry())) {
            ConnectionProvider provider = config.dispatchConnectionProvider(owner);
            assertThat(provider.maxConnections()).isEqualTo(42);
        }
    }

    @Test
    void theProviderIsDisposedWhenTheContextCloses() throws Exception {
        // The contract is the bean's lifecycle, so this exercises the real context rather than
        // calling dispose() by hand: the global provider the config used before is never
        // disposed at all, and its connections outlive the context that opened them.
        ConnectionProvider provider;
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{}"));
            server.start();
            try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
                context.registerBean(SimpleMeterRegistry.class);
                context.register(HttpClientConfig.class);
                context.refresh();
                provider = context.getBean(ConnectionProvider.class);

                WebClient client = context.getBean(WebClient.class);
                client.get().uri(server.url("/open-pool").uri()).retrieve().bodyToMono(String.class)
                        .block(Duration.ofSeconds(5));
                assertThat(provider.isDisposed()).isFalse();
            }
        }

        assertThat(provider.isDisposed())
                .as("the pool must have an owner that shuts it down, not outlive the context")
                .isTrue();
    }
}
