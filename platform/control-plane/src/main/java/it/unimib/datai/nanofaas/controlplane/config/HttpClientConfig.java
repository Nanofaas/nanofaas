package it.unimib.datai.nanofaas.controlplane.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.ChannelOption;
import io.netty.resolver.dns.NoopDnsCache;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

/**
 * Configuration for HTTP clients used by dispatchers.
 * Provides a properly configured WebClient with timeouts and an explicit connection pool.
 */
@Configuration
@EnableConfigurationProperties(HttpClientProperties.class)
public class HttpClientConfig {

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }

    /**
     * The dispatch connection pool, owned by this context rather than shared with whatever
     * else in the JVM happens to call {@code HttpClient.create()}.
     *
     * <p>This gives the global default three properties it did not have:
     *
     * <ul>
     *   <li><b>A stated budget.</b> Connections per destination and queued acquisitions are
     *       configuration, so the pool's memory and socket cost is something an operator
     *       chooses rather than inherits.</li>
     *   <li><b>A tunable wait.</b> The acquisition timeout is now a property. Its default is
     *       unchanged on purpose: shortening it measured as a large win only in a harness that
     *       never cancelled, and {@code ExternalDispatcher} does cancel (it wraps the call in
     *       {@code .timeout(functionTimeout)}, which cancels the pending acquisition too). With
     *       cancellation the value makes no measurable difference, so the tuning was not
     *       adopted — see docs/experiments/control-plane-tuning-2026-09/RESULTS.md.</li>
     *   <li><b>Finite retention.</b> Idle connections are evicted, optional maximum lifetime
     *       rotates old sockets, and empty inactive destination pools are disposed.</li>
     * </ul>
     *
     * <p>The owner bean is the only destroy target. The exported provider facade has inferred
     * destruction disabled, so context shutdown blocks on the owner's idempotent close exactly once.
     */
    @Bean(destroyMethod = "close")
    public DispatchConnectionPool dispatchConnectionPool(
            HttpClientProperties properties, MeterRegistry meterRegistry) {
        return new DispatchConnectionPool(properties, meterRegistry);
    }

    @Bean(destroyMethod = "")
    public ConnectionProvider dispatchConnectionProvider(DispatchConnectionPool owner) {
        return owner.provider();
    }

    @Bean
    public WebClient webClient(WebClient.Builder builder,
                               HttpClientProperties properties,
                               ConnectionProvider dispatchConnectionProvider) {
        HttpClient httpClient = dispatchHttpClient(properties, dispatchConnectionProvider);

        return builder
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(properties.maxInMemorySizeMb() * 1024 * 1024))
                .build();
    }

    /**
     * The dispatch client resolves on every new connection instead of caching DNS answers.
     *
     * <p>A function deleted and re-registered under the same name gets a new Service with a new
     * ClusterIP. Cached for the record's TTL (30 s on minikube's CoreDNS), the old answer kept
     * dispatches dialing the deleted ClusterIP, where nothing answers: each connect hung to the
     * connect timeout and invocations timed out although the new pod was Ready. A no-op cache,
     * not a zero TTL: Netty rejects a maximum TTL of zero. Connections are pooled, so a lookup
     * happens only when one is opened.
     */
    static HttpClient dispatchHttpClient(HttpClientProperties properties, ConnectionProvider provider) {
        return HttpClient.create(provider)
                .resolver(spec -> spec.resolveCache(NoopDnsCache.INSTANCE))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.connectTimeoutMs())
                .responseTimeout(Duration.ofMillis(properties.readTimeoutMs()));
    }
}
