package it.unimib.datai.nanofaas.controlplane.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.resolver.dns.NoopDnsCache;
import org.junit.jupiter.api.Test;
import reactor.netty.http.client.HttpClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A function deleted and re-registered under the same name gets a new Service with a new
 * ClusterIP. With the resolver caching each answer for the record's TTL (30 s on minikube's
 * CoreDNS), dispatches kept dialing the deleted ClusterIP; nothing answers there, so every
 * connect hung to the 5 s connect timeout and invocations timed out once the pod was Ready.
 * Connections are pooled, so resolving on every new connection costs little.
 */
class DispatchDnsCacheTest {

    @Test
    void theDispatchClientDoesNotCacheDnsAnswers() {
        HttpClientProperties properties = new HttpClientProperties(
                null, null, null, null, null, null, null, null, null, null, null);
        try (DispatchConnectionPool owner = new DispatchConnectionPool(properties, new SimpleMeterRegistry())) {
            HttpClient client = HttpClientConfig.dispatchHttpClient(properties, owner.provider());

            assertThat(client.configuration().getNameResolverProvider()).isNotNull();
            assertThat(client.configuration().getNameResolverProvider().resolveCache())
                    .isSameAs(NoopDnsCache.INSTANCE);
        }
    }
}
