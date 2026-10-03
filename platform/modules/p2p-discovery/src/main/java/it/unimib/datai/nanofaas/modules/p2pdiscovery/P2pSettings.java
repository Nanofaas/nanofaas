package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Layered selection settings: base (application.yml, then file config) overridden at runtime.
 * An override key mapped to null means "not applied" (e.g. maxLatencyMs=null removes the threshold).
 */
public final class P2pSettings {
    private static final Logger log = LoggerFactory.getLogger(P2pSettings.class);
    private static final String MAX_NEIGHBORS = "maxNeighbors";
    private static final String MAX_LATENCY_MS = "maxLatencyMs";

    private Integer baseMaxNeighbors;
    private Double baseMaxLatencyMs;
    public record Sharing(boolean functions, boolean images, boolean resources) {}
    private Sharing baseSharing = new Sharing(false, false, false);
    private final Map<String, Object> overrides = new HashMap<>();

    /** @throws IllegalArgumentException for a negative maxNeighbors or a non-positive maxLatencyMs */
    public P2pSettings(Integer maxNeighbors, Double maxLatencyMs) {
        setBase(maxNeighbors, maxLatencyMs);
    }

    public P2pSettings(Integer maxNeighbors, Double maxLatencyMs, Sharing sharing) {
        this(maxNeighbors, maxLatencyMs);
        baseSharing = sharing;
    }

    public synchronized void setBaseSharing(Sharing sharing) {
        baseSharing = sharing;
    }

    public synchronized Sharing sharing() {
        return new Sharing((Boolean) overrides.getOrDefault("shareFunctions", baseSharing.functions()),
                (Boolean) overrides.getOrDefault("shareImages", baseSharing.images()),
                (Boolean) overrides.getOrDefault("shareResources", baseSharing.resources()));
    }

    /** Same checks as the constructor; on rejection the previous base stays in place. */
    public synchronized void setBase(Integer maxNeighbors, Double maxLatencyMs) {
        validate(MAX_NEIGHBORS, maxNeighbors);
        validate(MAX_LATENCY_MS, maxLatencyMs);
        this.baseMaxNeighbors = maxNeighbors;
        this.baseMaxLatencyMs = maxLatencyMs;
    }

    public synchronized NeighborSelector.Settings effective() {
        Integer mn = baseMaxNeighbors;
        Double ml = baseMaxLatencyMs;
        if (overrides.containsKey(MAX_NEIGHBORS)) {
            mn = overrides.get(MAX_NEIGHBORS) == null ? null : ((Number) overrides.get(MAX_NEIGHBORS)).intValue();
        }
        if (overrides.containsKey(MAX_LATENCY_MS)) {
            ml = overrides.get(MAX_LATENCY_MS) == null ? null : ((Number) overrides.get(MAX_LATENCY_MS)).doubleValue();
        }
        return new NeighborSelector.Settings(mn, ml);
    }

    public synchronized Map<String, Object> overrides() {
        return new HashMap<>(overrides);
    }

    public synchronized void loadOverrides(Map<String, Object> loaded) {
        overrides.clear();
        if (loaded != null) {
            try {
                patch(loaded);
            } catch (IllegalArgumentException e) {
                overrides.clear();   // a bad saved override must not block startup
                log.warn("ignoring the saved p2p overrides: {}", e.getMessage());
            }
        }
    }

    public synchronized void clearOverrides() {
        overrides.clear();
    }

    /** Validates the whole patch first; applies nothing when any entry is invalid. */
    public synchronized void patch(Map<String, Object> patch) {
        for (Map.Entry<String, Object> e : patch.entrySet()) {
            validate(e.getKey(), e.getValue());
        }
        overrides.putAll(patch);
    }

    private static void validate(String key, Object v) {
        boolean valid = switch (key) {
            case "shareFunctions", "shareImages", "shareResources" -> v instanceof Boolean;
            case MAX_NEIGHBORS -> v == null || isNonNegativeInt(v);
            case MAX_LATENCY_MS -> v == null || isPositive(v);
            default -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException(switch (key) {
                case "shareFunctions", "shareImages", "shareResources" -> key + " must be a boolean";
                case MAX_NEIGHBORS -> "maxNeighbors must be an integer >= 0 or null";
                case MAX_LATENCY_MS -> "maxLatencyMs must be a number > 0 or null";
                default -> "unknown setting: " + key;
            });
        }
    }

    private static boolean isNonNegativeInt(Object v) {
        return v instanceof Number n && n.intValue() >= 0 && n.doubleValue() == n.intValue();
    }

    private static boolean isPositive(Object v) {
        return v instanceof Number n && !Double.isNaN(n.doubleValue()) && n.doubleValue() > 0;
    }
}
