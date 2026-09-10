package it.unimib.datai.nanofaas.controlplane.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.resolver.AddressResolverGroup;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.resources.ConnectionPoolMetrics;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.transport.TransportConfig;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Context-owned HTTP connection provider with aggregate, low-cardinality pool metrics.
 *
 * <p>Reactor Netty creates one bounded pool per concrete remote host. This owner keeps one
 * provider for the whole context, observes every destination pool through the provider's meter
 * registrar, and disposes the provider once. It deliberately does not claim that the per-host
 * connection limit is an aggregate admission limit; P07 owns that separate application-level
 * contract.
 */
public final class DispatchConnectionPool implements AutoCloseable, ConnectionProvider.MeterRegistrar {
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    private final ConcurrentMap<PoolKey, ConnectionPoolMetrics> pools = new ConcurrentHashMap<>();
    private final ConnectionProvider delegate;
    private final ConnectionProvider facade = new OwnedProvider();
    private final AtomicReference<Mono<Void>> disposal = new AtomicReference<>();

    public DispatchConnectionPool(HttpClientProperties properties, MeterRegistry registry) {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(registry, "registry");

        ConnectionProvider.Builder builder = ConnectionProvider.builder("nanofaas-dispatch")
                .maxConnections(properties.maxConnections())
                .pendingAcquireMaxCount(properties.pendingAcquireMaxCount())
                .pendingAcquireTimeout(Duration.ofMillis(properties.pendingAcquireTimeoutMs()))
                .maxIdleTime(Duration.ofMillis(properties.maxIdleTimeMs()))
                .evictInBackground(Duration.ofMillis(properties.evictionIntervalMs()))
                .disposeInactivePoolsInBackground(
                        Duration.ofMillis(properties.inactivePoolDisposeIntervalMs()),
                        Duration.ofMillis(properties.poolInactivityMs()))
                .metrics(true, () -> this);
        if (properties.maxLifeTimeMs() > 0) {
            builder.maxLifeTime(Duration.ofMillis(properties.maxLifeTimeMs()));
        }
        delegate = builder.build();

        Gauge.builder("nanofaas_http_pool_destinations", this, DispatchConnectionPool::destinationCount)
                .description("Current Reactor Netty destination-pool population")
                .register(registry);
        Gauge.builder("nanofaas_http_pool_connections", this, DispatchConnectionPool::connectionCount)
                .description("Current allocated HTTP connections across destination pools")
                .register(registry);
        Gauge.builder("nanofaas_http_pool_active_connections", this, DispatchConnectionPool::activeConnectionCount)
                .description("Current acquired HTTP connections across destination pools")
                .register(registry);
        Gauge.builder("nanofaas_http_pool_idle_connections", this, DispatchConnectionPool::idleConnectionCount)
                .description("Current idle HTTP connections across destination pools")
                .register(registry);
        Gauge.builder("nanofaas_http_pool_pending_acquisitions", this, DispatchConnectionPool::pendingAcquireCount)
                .description("Current pending HTTP connection acquisitions across destination pools")
                .register(registry);
    }

    public ConnectionProvider provider() {
        return facade;
    }

    public int destinationCount() {
        return pools.size();
    }

    public int connectionCount() {
        return pools.values().stream().mapToInt(ConnectionPoolMetrics::allocatedSize).sum();
    }

    public int activeConnectionCount() {
        return pools.values().stream().mapToInt(ConnectionPoolMetrics::acquiredSize).sum();
    }

    public int idleConnectionCount() {
        return pools.values().stream().mapToInt(ConnectionPoolMetrics::idleSize).sum();
    }

    public int pendingAcquireCount() {
        return pools.values().stream().mapToInt(ConnectionPoolMetrics::pendingAcquireSize).sum();
    }

    public boolean isDisposed() {
        return disposal.get() != null && delegate.isDisposed();
    }

    @Override
    public void registerMetrics(
            String poolName, String id, SocketAddress remoteAddress, ConnectionPoolMetrics metrics) {
        pools.put(new PoolKey(poolName, id, remoteAddress), metrics);
    }

    @Override
    public void deRegisterMetrics(String poolName, String id, SocketAddress remoteAddress) {
        pools.remove(new PoolKey(poolName, id, remoteAddress));
    }

    public Mono<Void> disposeLater() {
        Mono<Void> current = disposal.get();
        if (current != null) {
            return current;
        }
        Mono<Void> created = Mono.defer(delegate::disposeLater)
                .doFinally(_ -> pools.clear())
                .cache();
        return disposal.compareAndSet(null, created) ? created : disposal.get();
    }

    @Override
    public void close() {
        disposeLater().block(CLOSE_TIMEOUT);
    }

    private record PoolKey(String poolName, String id, SocketAddress remoteAddress) {
    }

    private final class OwnedProvider implements ConnectionProvider {
        @Override
        public Mono<? extends Connection> acquire(
                TransportConfig config,
                ConnectionObserver connectionObserver,
                @Nullable Supplier<? extends SocketAddress> remoteAddress,
                @Nullable AddressResolverGroup<?> resolverGroup) {
            if (disposal.get() != null) {
                return Mono.error(new IllegalStateException("dispatch connection pool is closed"));
            }
            return delegate.acquire(config, connectionObserver, remoteAddress, resolverGroup);
        }

        @Override
        public void disposeWhen(SocketAddress remoteAddress) {
            delegate.disposeWhen(remoteAddress);
        }

        @Override
        public void dispose() {
            disposeLater().subscribe();
        }

        @Override
        public Mono<Void> disposeLater() {
            return DispatchConnectionPool.this.disposeLater();
        }

        @Override
        public boolean isDisposed() {
            return DispatchConnectionPool.this.isDisposed();
        }

        @Override
        public int maxConnections() {
            return delegate.maxConnections();
        }

        @Override
        public @Nullable Map<SocketAddress, Integer> maxConnectionsPerHost() {
            return delegate.maxConnectionsPerHost();
        }

        @Override
        public ConnectionProvider.@Nullable Builder mutate() {
            return delegate.mutate();
        }

        @Override
        public @Nullable String name() {
            return delegate.name();
        }
    }
}
