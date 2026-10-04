package it.unimib.datai.nanofaas.modules.forecasting;

import it.unimib.datai.nanofaas.controlplane.registry.FunctionCatalogView;
import it.unimib.datai.nanofaas.controlplane.registry.ManagedReplicaControl;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import java.time.*;
import java.util.HashSet;

/** Registration-aware observation also counts complete windows with no arrivals. */
public final class ForecastObservationLifecycle implements SmartLifecycle {
    private final EwmaForecastSource source;
    private final FunctionCatalogView catalog;
    private final ManagedReplicaControl replicas;
    private final Clock clock;
    private Disposable loop;
    private volatile boolean running;
    public ForecastObservationLifecycle(EwmaForecastSource source, FunctionCatalogView catalog, ManagedReplicaControl replicas, Clock clock) {
        this.source = source; this.catalog = catalog; this.replicas = replicas; this.clock = clock;
    }
    synchronized void refresh() {
        if (catalog == null || replicas == null) return;
        var active = new HashSet<String>();
        for (var function : catalog.listRegistered()) {
            var generation = replicas.generationOf(function);
            if (generation == null) continue;
            active.add(generation.functionName() + "#" + generation.id());
            source.observe(generation.functionName(), generation.id(), clock.instant());
        }
        source.retain(active);
    }
    @Override public synchronized void start() {
        if (running) return;
        running = true;
        refresh();
        loop = Flux.interval(Duration.ofSeconds(1)).subscribe(i -> refresh());
    }
    @Override public synchronized void stop() { running = false; if (loop != null) loop.dispose(); }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 2050; }
}
