package it.unimib.datai.nanofaas.controlplane.config;

import io.netty.channel.ChannelOption;
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
     * <p>Two things this buys, both of which the global default did not give:
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
     * </ul>
     *
     * <p>{@code destroyMethod} gives the pool the explicit lifecycle it lacked: the global
     * provider is never disposed, so its connections outlive the context that opened them.
     */
    @Bean(destroyMethod = "dispose")
    public ConnectionProvider dispatchConnectionProvider(HttpClientProperties properties) {
        return ConnectionProvider.builder("nanofaas-dispatch")
                .maxConnections(properties.maxConnections())
                .pendingAcquireMaxCount(properties.pendingAcquireMaxCount())
                .pendingAcquireTimeout(Duration.ofMillis(properties.pendingAcquireTimeoutMs()))
                .build();
    }

    @Bean
    public WebClient webClient(WebClient.Builder builder,
                               HttpClientProperties properties,
                               ConnectionProvider dispatchConnectionProvider) {
        HttpClient httpClient = HttpClient.create(dispatchConnectionProvider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.connectTimeoutMs())
                .responseTimeout(Duration.ofMillis(properties.readTimeoutMs()));

        return builder
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(properties.maxInMemorySizeMb() * 1024 * 1024))
                .build();
    }
}
