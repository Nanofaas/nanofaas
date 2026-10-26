package it.unimib.datai.nanofaas.modules.forecasting;

import it.unimib.datai.nanofaas.controlplane.config.RuntimeConfigExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The {@code forecasting} runtime-config namespace: which provider answers forecast queries, and
 * the Holt and ARI parameters. Every provider keeps observing, so a switch takes effect on the next
 * query. Alpha, window, max age and history stay as started, because existing series were built
 * with them, and a change lasts until restart.
 */
final class ForecastingRuntimeConfigExtension implements RuntimeConfigExtension {
    private static final String PROVIDER = "provider";
    private static final String HOLT_LEVEL = "holtLevel";
    private static final String HOLT_TREND = "holtTrend";
    private static final String HOLT_DAMPING = "holtDamping";
    private static final String ARI_ORDER = "ariOrder";
    private static final Set<String> EDITABLE = Set.of(PROVIDER, HOLT_LEVEL, HOLT_TREND, HOLT_DAMPING, ARI_ORDER);
    private static final List<String> AVAILABLE = Arrays.stream(ForecastingProperties.Provider.values())
            .map(ForecastingProperties.Provider::id).toList();

    private final ForecastSelection selection;
    private final int history;

    ForecastingRuntimeConfigExtension(ForecastSelection selection, int history) {
        this.selection = selection;
        this.history = history;
    }

    @Override
    public String namespace() {
        return "forecasting";
    }

    @Override
    public Map<String, Object> snapshot() {
        ForecastSelection.Settings current = selection.get();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(PROVIDER, current.provider().id());
        result.put("available", AVAILABLE);
        result.put(HOLT_LEVEL, current.holt().level());
        result.put(HOLT_TREND, current.holt().trend());
        result.put(HOLT_DAMPING, current.holt().damping());
        result.put(ARI_ORDER, current.ari().order());
        result.put("persistence", "restart");
        return Map.copyOf(result);
    }

    @Override
    public List<String> validate(Map<String, Object> patch) {
        if (patch.isEmpty()) {
            return List.of("forecasting patch must not be empty");
        }
        if (!EDITABLE.containsAll(patch.keySet())) {
            return List.of("forecasting patch contains unsupported keys");
        }
        List<String> errors = new ArrayList<>();
        if (patch.containsKey(PROVIDER) && !AVAILABLE.contains(patch.get(PROVIDER))) {
            errors.add(PROVIDER + " must be one of the available providers");
        }
        for (String key : List.of(HOLT_LEVEL, HOLT_TREND, HOLT_DAMPING)) {
            if (patch.containsKey(key) && !(patch.get(key) instanceof Number number
                    && TrendForecastSource.unit(number.doubleValue()))) {
                errors.add(key + " must be a number in (0, 1]");
            }
        }
        if (patch.containsKey(ARI_ORDER)) {
            Integer order = integer(patch.get(ARI_ORDER));
            if (order == null || order < 1 || order > 64 || 2 * order + 2 > history) {
                errors.add(ARI_ORDER + " must be an integer in [1, " + Math.min(64, (history - 2) / 2) + "]");
            }
        }
        return List.copyOf(errors);
    }

    @Override
    public void apply(Map<String, Object> patch) {
        Map<String, Object> candidate = new LinkedHashMap<>(snapshot());
        candidate.putAll(patch);
        restore(candidate);
    }

    @Override
    public void restore(Map<String, Object> snapshot) {
        selection.set(new ForecastSelection.Settings(
                ForecastingProperties.Provider.valueOf(((String) snapshot.get(PROVIDER)).toUpperCase(Locale.ROOT)),
                new TrendForecastSource.Holt(number(snapshot, HOLT_LEVEL), number(snapshot, HOLT_TREND),
                        number(snapshot, HOLT_DAMPING)),
                new TrendForecastSource.Ari(integer(snapshot.get(ARI_ORDER)))));
    }

    private static double number(Map<String, Object> values, String key) {
        return ((Number) values.get(key)).doubleValue();
    }

    private static Integer integer(Object value) {
        if (!(value instanceof Number number)) {
            return null;
        }
        try {
            return new BigDecimal(number.toString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException _) {
            return null;
        }
    }
}
