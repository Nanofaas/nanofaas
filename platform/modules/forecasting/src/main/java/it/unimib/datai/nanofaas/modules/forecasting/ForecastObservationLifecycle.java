package it.unimib.datai.nanofaas.modules.forecasting;

import it.unimib.datai.nanofaas.controlplane.registry.FunctionCatalogView;
import it.unimib.datai.nanofaas.controlplane.registry.ManagedReplicaControl;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import java.time.*;
import java.util.HashSet;
import java.util.List;

/** Registration-aware observation also counts complete windows with no arrivals. */
public final class ForecastObservationLifecycle implements SmartLifecycle {
    private final List<? extends WindowedForecastSource<?>> sources;
    private final FunctionCatalogView catalog;
    private final ManagedReplicaControl replicas;
    private final Clock clock;
    private final boolean enabled;
    private Disposable loop;
    private volatile boolean running;
    public ForecastObservationLifecycle(WindowedForecastSource<?> source, FunctionCatalogView catalog, ManagedReplicaControl replicas, Clock clock) {
        this(List.of(source),catalog,replicas,clock,true);
    }
    public ForecastObservationLifecycle(List<? extends WindowedForecastSource<?>> sources, FunctionCatalogView catalog, ManagedReplicaControl replicas, Clock clock,boolean enabled) {
        this.sources = List.copyOf(sources); this.catalog = catalog; this.replicas = replicas; this.clock = clock;this.enabled=enabled;
    }
    synchronized void refresh() {
        if (catalog == null || replicas == null) return;
        var active = new HashSet<String>();
        var generations = new java.util.ArrayList<it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration>();
        for (var function : catalog.listRegistered()) {
            var generation = replicas.generationOf(function);
            if (generation == null) continue;
            active.add(generation.functionName() + "#" + generation.id());
            generations.add(generation);
        }
        var now = clock.instant();
        for (var source : sources) {
            source.retain(active);
            for (var generation : generations) source.observe(generation.functionName(), generation.id(), now);
        }
    }
    @Override public synchronized void start() {
        if (running || !enabled) return;
        running = true;
        refresh();
        loop = Flux.interval(Duration.ofSeconds(1)).subscribe(i -> refresh());
    }
    @Override public synchronized void stop() { running = false; if (loop != null) loop.dispose(); }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 2050; }
}
