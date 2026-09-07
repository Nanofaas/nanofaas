package it.unimib.datai.nanofaas.controlplane.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.resources.ConnectionProvider;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T1: the connection pool is an explicit, bounded budget with an explicit lifecycle,
 * not whatever Reactor Netty's global defaults happen to be.
 */
class HttpClientPoolTest {

    private HttpServer server;
    private ExecutorService serverPool;
    private String slowUrl;

    @BeforeEach
    void startBackend() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverPool = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "pool-test-backend");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(serverPool);
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        slowUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/slow";
    }

    @AfterEach
    void stopBackend() {
        server.stop(0);
        serverPool.shutdownNow();
    }

    @Test
    void theConfiguredConnectionBudgetReachesTheProvider() {
        HttpClientConfig config = new HttpClientConfig();
        HttpClientProperties properties = new HttpClientProperties(null, null, null, 42, null, null);

        ConnectionProvider provider = config.dispatchConnectionProvider(properties);

        assertThat(provider.maxConnections()).isEqualTo(42);
        provider.disposeLater().block(Duration.ofSeconds(5));
    }

    @Test
    void aRequestThatCannotGetAConnectionFailsAtTheAcquireBoundInsteadOfWaitingItOut() {
        // One connection, a backend that holds it for 1.5s, and a 200ms acquire bound.
        // The second request must be refused at ~200ms. Before T1 it would have queued for
        // Reactor Netty's 45s default — long past any caller's budget, and it would still
        // have made the backend serve a response nobody was waiting for.
        HttpClientConfig config = new HttpClientConfig();
        HttpClientProperties properties = new HttpClientProperties(null, null, null, 1, 4, 200);
        ConnectionProvider provider = config.dispatchConnectionProvider(properties);
        WebClient client = config.webClient(WebClient.builder(), properties, provider);
        try {
            // Occupy the only connection.
            client.get().uri(slowUrl).retrieve().bodyToMono(String.class).subscribe();
            Thread.sleep(200);

            long started = System.nanoTime();
            assertThatThrownBy(() -> client.get().uri(slowUrl).retrieve()
                    .bodyToMono(String.class).block(Duration.ofSeconds(10)))
                    .isNotNull();
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;

            assertThat(elapsedMs)
                    .as("the acquisition must be given up at its bound, not waited out")
                    .isLessThan(1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            provider.disposeLater().block(Duration.ofSeconds(5));
        }
    }

    @Test
    void theProviderIsDisposedWhenTheContextCloses() {
        // The contract is the bean's lifecycle, so this exercises the real context rather than
        // calling dispose() by hand: the global provider the config used before is never
        // disposed at all, and its connections outlive the context that opened them.
        ConnectionProvider provider;
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(HttpClientConfig.class);
            context.refresh();
            provider = context.getBean(ConnectionProvider.class);

            // Open a pool so "disposed" means something: a provider that never served a
            // request reports disposed already, and asserting on that would prove nothing.
            WebClient client = context.getBean(WebClient.class);
            client.get().uri(slowUrl).retrieve().bodyToMono(String.class)
                    .onErrorReturn("").block(Duration.ofSeconds(5));
            assertThat(provider.isDisposed()).isFalse();
        }

        assertThat(provider.isDisposed())
                .as("the pool must have an owner that shuts it down, not outlive the context")
                .isTrue();
    }
}
