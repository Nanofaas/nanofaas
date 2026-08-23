package it.unimib.datai.nanofaas.controlplane.execution;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;

@Component
public class ExecutionStore {
    private final Map<String, StoredExecution> executions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService janitor;
    private final Duration cleanupTtl;
    private final Duration ttl;
    private final Duration maxLifetime;

    public ExecutionStore() {
        this(new ExecutionStoreProperties(null, null, null));
    }

    // @Autowired is required: with two constructors Spring would otherwise pick the
    // no-arg one and silently ignore the configured properties.
    @Autowired
    public ExecutionStore(ExecutionStoreProperties properties, MeterRegistry registry) {
        this(properties);
        // Quanto la piattaforma sta ricordando.
        //
        // La ritenzione qui e' dichiarata nel tempo e illimitata nello spazio: i
        // record scadono dopo `ttl`, ma niente limita quanti se ne accumulano dentro
        // quella finestra, quindi la memoria necessaria e' proporzionale al tasso di
        // arrivo. Il 2026-08-23 l'heap saliva di 2,79 MB/s per tutti gli 8 minuti di
        // un run a 2x senza mai scendere, e a 3x il collector seriale finiva per
        // impiegare il 50,6% del tempo con pause da 2,851 s - abbastanza da far
        // fallire un probe di liveness con un secondo di budget, e da far uccidere il
        // container con tutto lo stato dentro.
        //
        // Quale struttura tenesse quei byte era pero' un'inferenza, non una misura:
        // questa e' la misura. Un gauge a supplier, letto allo scrape, niente sul
        // percorso caldo.
        Gauge.builder("execution_store_size", executions::size).register(registry);
    }

    /** Pacchetto-privato: le prove di sfratto costruiscono il negozio senza registro. */
    ExecutionStore(ExecutionStoreProperties properties) {
        this.ttl = properties.ttl();
        this.cleanupTtl = properties.cleanupTtl();
        this.maxLifetime = properties.maxLifetime();
        this.janitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "execution-store-janitor");
            t.setDaemon(true);
            return t;
        });
        janitor.scheduleAtFixedRate(this::evictExpired, 1, 1, TimeUnit.MINUTES);
    }

    /** Quanti record sono archiviati adesso. */
    public int size() {
        return executions.size();
    }

    public void put(ExecutionRecord executionRecord) {
        executions.put(executionRecord.executionId(), new StoredExecution(executionRecord, Instant.now()));
    }

    public Optional<ExecutionRecord> get(String executionId) {
        StoredExecution stored = executions.get(executionId);
        if (stored == null) {
            return Optional.empty();
        }
        return Optional.of(stored.executionRecord());
    }

    /**
     * Hot-path lookup without Optional allocation.
     */
    public ExecutionRecord getOrNull(String executionId) {
        StoredExecution stored = executions.get(executionId);
        return stored == null ? null : stored.executionRecord();
    }

    public void remove(String executionId) {
        executions.remove(executionId);
    }

    // Package-private for deterministic testing.
    void evictExpired() {
        Instant now = Instant.now();
        Instant cutoff = now.minus(ttl);
        Instant cleanupCutoff = now.minus(cleanupTtl);
        Instant lifetimeCutoff = now.minus(maxLifetime);

        executions.entrySet().removeIf(entry -> {
            StoredExecution stored = entry.getValue();
            ExecutionRecord executionRecord = stored.executionRecord();
            Instant created = stored.createdAt();
            if (!executionRecord.isTerminal()) {
                // Stuck executions (lost dispatch, missing callback) must not leak forever.
                return created.isBefore(lifetimeCutoff);
            }

            Instant completedAt = executionRecord.finishedAt();
            Instant retentionAnchor = completedAt == null ? created : completedAt;

            if (retentionAnchor.isBefore(cutoff)) {
                return true;
            }
            if (retentionAnchor.isBefore(cleanupCutoff)) {
                executionRecord.cleanup();
            }
            return false;
        });
    }

    @PreDestroy
    public void shutdown() {
        janitor.shutdownNow();
    }

    private record StoredExecution(ExecutionRecord executionRecord, Instant createdAt) {
    }
}
