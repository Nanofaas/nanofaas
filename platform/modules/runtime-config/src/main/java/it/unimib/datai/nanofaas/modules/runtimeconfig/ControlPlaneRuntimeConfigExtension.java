package it.unimib.datai.nanofaas.modules.runtimeconfig;

import it.unimib.datai.nanofaas.controlplane.config.RuntimeConfigExtension;
import it.unimib.datai.nanofaas.controlplane.capacity.AdmissionLimitsControl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ControlPlaneRuntimeConfigExtension implements RuntimeConfigExtension {
    private static final String RATE_MAX_PER_SECOND = "rateMaxPerSecond";
    private static final String EXECUTIONS_GLOBAL = "maxExecutionsGlobal";
    private static final String EXECUTIONS_PER_FUNCTION = "maxExecutionsPerFunction";
    private static final String CANONICAL_INPUT_GLOBAL = "maxCanonicalInputBytesGlobal";
    private static final String CANONICAL_INPUT_PER_FUNCTION = "maxCanonicalInputBytesPerFunction";
    private static final String PHYSICAL_INPUT_GLOBAL = "maxPhysicalInputCopyBytesGlobal";
    private static final String PHYSICAL_INPUT_PER_FUNCTION = "maxPhysicalInputCopyBytesPerFunction";
    private static final String WAITERS_GLOBAL = "maxWaitersGlobal";
    private static final String WAITERS_PER_FUNCTION = "maxWaitersPerFunction";
    private static final Set<String> SUPPORTED = Set.of(
            RATE_MAX_PER_SECOND,
            EXECUTIONS_GLOBAL, EXECUTIONS_PER_FUNCTION,
            CANONICAL_INPUT_GLOBAL, CANONICAL_INPUT_PER_FUNCTION,
            PHYSICAL_INPUT_GLOBAL, PHYSICAL_INPUT_PER_FUNCTION,
            WAITERS_GLOBAL, WAITERS_PER_FUNCTION);

    private final AdmissionLimitsControl limits;

    ControlPlaneRuntimeConfigExtension(AdmissionLimitsControl limits) {
        this.limits = limits;
    }

    @Override
    public String namespace() {
        return "control-plane";
    }

    @Override
    public Map<String, Object> snapshot() {
        AdmissionLimitsControl.Snapshot current = limits.limits();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(RATE_MAX_PER_SECOND, current.rateMaxPerSecond());
        result.put(EXECUTIONS_GLOBAL, current.executions().global());
        result.put(EXECUTIONS_PER_FUNCTION, current.executions().perFunction());
        result.put(CANONICAL_INPUT_GLOBAL, current.canonicalInputBytes().global());
        result.put(CANONICAL_INPUT_PER_FUNCTION, current.canonicalInputBytes().perFunction());
        result.put(PHYSICAL_INPUT_GLOBAL, current.physicalInputCopyBytes().global());
        result.put(PHYSICAL_INPUT_PER_FUNCTION, current.physicalInputCopyBytes().perFunction());
        result.put(WAITERS_GLOBAL, current.waiters().global());
        result.put(WAITERS_PER_FUNCTION, current.waiters().perFunction());
        return Map.copyOf(result);
    }

    @Override
    public List<String> validate(Map<String, Object> patch) {
        if (patch.isEmpty()) {
            return List.of("control-plane patch must not be empty");
        }
        if (!SUPPORTED.containsAll(patch.keySet())) {
            return List.of("control-plane patch contains unsupported keys");
        }
        List<String> errors = new ArrayList<>();
        if (patch.containsKey(RATE_MAX_PER_SECOND) && !isPositiveInt(patch.get(RATE_MAX_PER_SECOND))) {
            errors.add("rateMaxPerSecond must be a positive integer");
        }
        for (String key : SUPPORTED) {
            if (!RATE_MAX_PER_SECOND.equals(key) && patch.containsKey(key)
                    && positiveLong(patch.get(key)) == null) {
                errors.add(key + " must be a positive integer");
            }
        }
        if (!errors.isEmpty()) {
            return List.copyOf(errors);
        }
        Map<String, Object> candidate = new LinkedHashMap<>(snapshot());
        candidate.putAll(patch);
        validatePair(candidate, EXECUTIONS_GLOBAL, EXECUTIONS_PER_FUNCTION, errors);
        validatePair(candidate, CANONICAL_INPUT_GLOBAL, CANONICAL_INPUT_PER_FUNCTION, errors);
        validatePair(candidate, PHYSICAL_INPUT_GLOBAL, PHYSICAL_INPUT_PER_FUNCTION, errors);
        validatePair(candidate, WAITERS_GLOBAL, WAITERS_PER_FUNCTION, errors);
        return List.copyOf(errors);
    }

    private boolean isPositiveInt(Object value) {
        if (!(value instanceof Number number)) {
            return false;
        }
        try {
            return new BigDecimal(number.toString()).intValueExact() > 0;
        } catch (ArithmeticException | NumberFormatException _) {
            return false;
        }
    }

    @Override
    public void apply(Map<String, Object> patch) {
        Map<String, Object> candidate = new LinkedHashMap<>(snapshot());
        candidate.putAll(patch);
        applyComplete(candidate);
    }

    @Override
    public void restore(Map<String, Object> snapshot) {
        applyComplete(snapshot);
    }

    private void applyComplete(Map<String, Object> values) {
        limits.updateLimits(new AdmissionLimitsControl.Snapshot(
                Math.toIntExact(requiredLong(values, RATE_MAX_PER_SECOND)),
                pair(values, EXECUTIONS_GLOBAL, EXECUTIONS_PER_FUNCTION),
                pair(values, CANONICAL_INPUT_GLOBAL, CANONICAL_INPUT_PER_FUNCTION),
                pair(values, PHYSICAL_INPUT_GLOBAL, PHYSICAL_INPUT_PER_FUNCTION),
                pair(values, WAITERS_GLOBAL, WAITERS_PER_FUNCTION)));
    }

    private static AdmissionLimitsControl.Pair pair(Map<String, Object> values, String global, String perFunction) {
        return new AdmissionLimitsControl.Pair(requiredLong(values, global), requiredLong(values, perFunction));
    }

    private static void validatePair(Map<String, Object> values, String global, String perFunction,
                                     List<String> errors) {
        if (requiredLong(values, perFunction) > requiredLong(values, global)) {
            errors.add(perFunction + " must not exceed " + global);
        }
    }

    private static long requiredLong(Map<String, Object> values, String key) {
        Long result = positiveLong(values.get(key));
        if (result == null) {
            throw new IllegalArgumentException(key + " must be a positive integer");
        }
        return result;
    }

    private static Long positiveLong(Object value) {
        if (!(value instanceof Number number)) {
            return null;
        }
        try {
            long parsed = new BigDecimal(number.toString()).longValueExact();
            return parsed > 0 ? parsed : null;
        } catch (ArithmeticException | NumberFormatException _) {
            return null;
        }
    }
}
