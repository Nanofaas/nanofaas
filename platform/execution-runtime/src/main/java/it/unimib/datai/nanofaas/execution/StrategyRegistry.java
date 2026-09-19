package it.unimib.datai.nanofaas.execution;

import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingStrategy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The strategies this artifact was built with, by id. Only ids actually present here can be
 * selected: an unknown id is refused, nothing is loaded at runtime.
 */
public final class StrategyRegistry {

    private final Map<String, SchedulingStrategy> byId = new LinkedHashMap<>();

    public StrategyRegistry(List<SchedulingStrategy> strategies) {
        Objects.requireNonNull(strategies, "strategies must not be null");
        Map<String, SchedulingStrategy> sorted = new TreeMap<>();
        for (SchedulingStrategy strategy : strategies) {
            String id = strategy.id();
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException(
                        "strategy id must not be blank: " + strategy.getClass().getName());
            }
            if (sorted.put(id, strategy) != null) {
                throw new IllegalArgumentException("duplicate strategy id: " + id);
            }
        }
        byId.putAll(sorted);
    }

    /**
     * The strategy registered under this id.
     *
     * @throws IllegalArgumentException when no strategy with this id was built into the artifact
     */
    public SchedulingStrategy require(String id) {
        SchedulingStrategy strategy = byId.get(id);
        if (strategy == null) {
            throw new IllegalArgumentException("unknown scheduling strategy: " + id + ", available " + ids());
        }
        return strategy;
    }

    /** Every available id, in a stable (alphabetical) order. */
    public List<String> ids() {
        return List.copyOf(byId.keySet());
    }
}
