package it.unimib.datai.nanofaas.modules.runtimeconfig;

import it.unimib.datai.nanofaas.controlplane.service.RateLimiter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ControlPlaneRuntimeConfigExtension implements RuntimeConfigExtension {
    private static final String RATE_MAX_PER_SECOND = "rateMaxPerSecond";

    private final RateLimiter rateLimiter;

    ControlPlaneRuntimeConfigExtension(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public String namespace() {
        return "control-plane";
    }

    @Override
    public Map<String, Object> snapshot() {
        return Map.of(RATE_MAX_PER_SECOND, rateLimiter.getMaxPerSecond());
    }

    @Override
    public List<String> validate(Map<String, Object> patch) {
        if (!patch.keySet().equals(Set.of(RATE_MAX_PER_SECOND))) {
            return List.of("control-plane supports only rateMaxPerSecond");
        }
        Object value = patch.get(RATE_MAX_PER_SECOND);
        if (!isPositiveInt(value)) {
            return List.of("rateMaxPerSecond must be a positive integer");
        }
        return List.of();
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
        rateLimiter.setMaxPerSecond(((Number) patch.get(RATE_MAX_PER_SECOND)).intValue());
    }

    @Override
    public void restore(Map<String, Object> snapshot) {
        rateLimiter.setMaxPerSecond(((Number) snapshot.get(RATE_MAX_PER_SECOND)).intValue());
    }
}
