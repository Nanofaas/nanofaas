package it.unimib.datai.nanofaas.controlplane.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.resolver.AddressResolverGroup;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistrationListener;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.resources.ConnectionPoolMetrics;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.transport.TransportConfig;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;

/**
 * Context-owned HTTP connection provider with aggregate, low-cardinality pool metrics.
 *
 * <p>Reactor Netty creates one bounded pool per concrete remote host. This owner keeps one
 * provider for the whole context, observes every destination pool through the provider's meter
 * registrar, and disposes the provider once. It deliberately does not claim that the per-host
 * connection limit is an aggregate admission limit; P07 owns that separate application-level
 * contract.
 */
public final class DispatchConnectionPool implements AutoCloseable, ConnectionProvider.MeterRegistrar,
        FunctionRegistrationListener {
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);
    private static final AtomicInteger SCHEDULER_IDS = new AtomicInteger();
    private static final long NOT_EMPTY = Long.MIN_VALUE;

    private final ConcurrentMap<PoolKey, TrackedPool> pools = new ConcurrentHashMap<>();
    private final Map<String, SocketAddress> functionDestinations = new ConcurrentHashMap<>();
    private final ConnectionProvider delegate;
    private final ConnectionProvider facade = new OwnedProvider();
    private final MeterRegistry meterRegistry;
    private final List<Meter> aggregateMeters;
    private final ScheduledThreadPoolExecutor inactivePoolScheduler;
    private final ScheduledFuture<?> inactivePoolTask;
    private final LongSupplier nanoTime;
    private final long poolInactivityNanos;
    private final AtomicReference<Mono<Void>> disposal = new AtomicReference<>();
    private final AtomicBoolean schedulerStopped = new AtomicBoolean();
    private final AtomicBoolean resourcesReleased = new AtomicBoolean();

    public DispatchConnectionPool(HttpClientProperties properties, MeterRegistry registry) {
        this(properties, registry, newInactivePoolScheduler(), System::nanoTime);
    }

    DispatchConnectionPool(HttpClientProperties properties, MeterRegistry registry,
                           ScheduledThreadPoolExecutor scheduler, LongSupplier nanoTime) {
        Objects.requireNonNull(properties, "properties");
        this.meterRegistry = Objects.requireNonNull(registry, "registry");
        this.inactivePoolScheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        poolInactivityNanos = Duration.ofMillis(properties.poolInactivityMs()).toNanos();
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);

        ConnectionProvider.Builder builder = ConnectionProvider.builder("nanofaas-dispatch")
                .maxConnections(properties.maxConnections())
                .pendingAcquireMaxCount(properties.pendingAcquireMaxCount())
                .pendingAcquireTimeout(Duration.ofMillis(properties.pendingAcquireTimeoutMs()))
                .maxIdleTime(Duration.ofMillis(properties.maxIdleTimeMs()))
                .evictInBackground(Duration.ofMillis(properties.evictionIntervalMs()))
                .metrics(true, () -> this);
        if (properties.maxLifeTimeMs() > 0) {
            builder.maxLifeTime(Duration.ofMillis(properties.maxLifeTimeMs()));
        }
        delegate = builder.build();

        aggregateMeters = List.of(
                gauge("nanofaas_http_pool_destinations", "Current Reactor Netty destination-pool population",
                        DispatchConnectionPool::destinationCount),
                gauge("nanofaas_http_pool_connections", "Current allocated HTTP connections across destination pools",
                        DispatchConnectionPool::connectionCount),
                gauge("nanofaas_http_pool_active_connections", "Current acquired HTTP connections across destination pools",
                        DispatchConnectionPool::activeConnectionCount),
                gauge("nanofaas_http_pool_idle_connections", "Current idle HTTP connections across destination pools",
                        DispatchConnectionPool::idleConnectionCount),
                gauge("nanofaas_http_pool_pending_acquisitions",
                        "Current pending HTTP connection acquisitions across destination pools",
                        DispatchConnectionPool::pendingAcquireCount));

        long intervalMillis = properties.inactivePoolDisposeIntervalMs();
        inactivePoolTask = scheduler.scheduleWithFixedDelay(
                this::disposeInactivePools, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    public ConnectionProvider provider() {
        return facade;
    }

    public int destinationCount() {
        return pools.size();
    }

    public int connectionCount() {
        return pools.values().stream().mapToInt(pool -> pool.metrics().allocatedSize()).sum();
    }

    public int activeConnectionCount() {
        return pools.values().stream().mapToInt(pool -> pool.metrics().acquiredSize()).sum();
    }

    public int idleConnectionCount() {
        return pools.values().stream().mapToInt(pool -> pool.metrics().idleSize()).sum();
    }

    public int pendingAcquireCount() {
        return pools.values().stream().mapToInt(pool -> pool.metrics().pendingAcquireSize()).sum();
    }

    public boolean isDisposed() {
        return disposal.get() != null && delegate.isDisposed();
    }

    @Override
    public void registerMetrics(
            String poolName, String id, SocketAddress remoteAddress, ConnectionPoolMetrics metrics) {
        pools.put(new PoolKey(poolName, id, remoteAddress), new TrackedPool(metrics));
    }

    @Override
    public void deRegisterMetrics(String poolName, String id, SocketAddress remoteAddress) {
        pools.remove(new PoolKey(poolName, id, remoteAddress));
    }

    @Override
    public void onRegister(FunctionSpec spec) {
        SocketAddress next = endpointAddress(spec.endpointUrl());
        SocketAddress retired = null;
        synchronized (functionDestinations) {
            SocketAddress previous = next == null
                    ? functionDestinations.remove(spec.name())
                    : functionDestinations.put(spec.name(), next);
            if (previous != null && !previous.equals(next) && !functionDestinations.containsValue(previous)) {
                retired = previous;
            }
        }
        disposeDestination(retired);
    }

    @Override
    public void onRemove(String functionName) {
        SocketAddress retired;
        synchronized (functionDestinations) {
            SocketAddress previous = functionDestinations.remove(functionName);
            retired = previous != null && !functionDestinations.containsValue(previous) ? previous : null;
        }
        disposeDestination(retired);
    }

    public Mono<Void> disposeLater() {
        Mono<Void> current = disposal.get();
        if (current != null) {
            return current;
        }
        Mono<Void> created = Mono.defer(delegate::disposeLater)
                .doFinally(_ -> releaseOwnedResources())
                .cache();
        if (disposal.compareAndSet(null, created)) {
            stopInactivePoolScheduler();
            return created;
        }
        return disposal.get();
    }

    @Override
    public void close() {
        try {
            disposeLater().block(CLOSE_TIMEOUT);
        } finally {
            awaitSchedulerTermination();
        }
    }

    private Meter gauge(String name, String description,
                        ToDoubleFunction<DispatchConnectionPool> value) {
        return Gauge.builder(name, this, value).description(description).register(meterRegistry);
    }

    private void disposeInactivePools() {
        if (disposal.get() != null) {
            return;
        }
        long now = nanoTime.getAsLong();
        Set<SocketAddress> inactive = new HashSet<>();
        pools.forEach((key, pool) -> {
            if (!pool.empty()) {
                pool.emptySinceNanos().set(NOT_EMPTY);
                return;
            }
            long emptySince = pool.emptySinceNanos().get();
            if (emptySince == NOT_EMPTY) {
                pool.emptySinceNanos().compareAndSet(NOT_EMPTY, now);
            } else if (now - emptySince >= poolInactivityNanos) {
                inactive.add(key.remoteAddress());
            }
        });
        inactive.forEach(this::disposeDestination);
    }

    private void disposeDestination(@Nullable SocketAddress remoteAddress) {
        if (remoteAddress != null && disposal.get() == null) {
            delegate.disposeWhen(remoteAddress);
        }
    }

    private void stopInactivePoolScheduler() {
        if (schedulerStopped.compareAndSet(false, true)) {
            inactivePoolTask.cancel(true);
            inactivePoolScheduler.shutdownNow();
        }
    }

    private void awaitSchedulerTermination() {
        stopInactivePoolScheduler();
        try {
            if (!inactivePoolScheduler.awaitTermination(CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("dispatch inactive-pool scheduler did not terminate");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while closing dispatch inactive-pool scheduler", interrupted);
        }
    }

    private void releaseOwnedResources() {
        if (resourcesReleased.compareAndSet(false, true)) {
            pools.clear();
            functionDestinations.clear();
            aggregateMeters.forEach(meterRegistry::remove);
        }
    }

    private static ScheduledThreadPoolExecutor newInactivePoolScheduler() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable,
                    "nanofaas-dispatch-pool-evictor-" + SCHEDULER_IDS.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return scheduler;
    }

    private static @Nullable SocketAddress endpointAddress(@Nullable String endpointUrl) {
        if (endpointUrl == null || endpointUrl.isBlank()) {
            return null;
        }
        URI endpoint = URI.create(endpointUrl);
        if (endpoint.getHost() == null) {
            return null;
        }
        int port = endpoint.getPort();
        if (port < 0) {
            port = "https".equalsIgnoreCase(endpoint.getScheme()) ? 443 : 80;
        }
        return InetSocketAddress.createUnresolved(endpoint.getHost(), port);
    }

    private record PoolKey(String poolName, String id, SocketAddress remoteAddress) {
    }

    private record TrackedPool(ConnectionPoolMetrics metrics, AtomicLong emptySinceNanos) {
        private TrackedPool(ConnectionPoolMetrics metrics) {
            this(metrics, new AtomicLong(NOT_EMPTY));
        }

        private boolean empty() {
            return metrics.allocatedSize() == 0
                    && metrics.acquiredSize() == 0
                    && metrics.idleSize() == 0
                    && metrics.pendingAcquireSize() == 0;
        }
    }

    private final class OwnedProvider implements ConnectionProvider {
        @Override
        public Mono<? extends Connection> acquire(
                TransportConfig config, ConnectionObserver connectionObserver,
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
