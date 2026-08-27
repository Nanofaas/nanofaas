package it.unimib.datai.nanofaas.modules.syncqueue;

import it.unimib.datai.nanofaas.modules.runtimeconfig.RuntimeConfigExtension;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@AutoConfiguration
@AutoConfigureAfter(name = "it.unimib.datai.nanofaas.modules.syncqueue.SyncQueueConfiguration")
@ConditionalOnClass(name = "it.unimib.datai.nanofaas.modules.runtimeconfig.RuntimeConfigExtension")
public class SyncQueueRuntimeConfigAutoConfiguration {
    @Bean
    RuntimeConfigExtension syncQueueRuntimeConfigExtension(MutableSyncQueueConfigSource source) {
        return new RuntimeConfigExtension() {
            @Override public String namespace() { return "sync-queue"; }
            @Override public Map<String, Object> snapshot() { return source.snapshot(); }
            @Override public List<String> validate(Map<String, Object> patch) {
                List<String> errors = new ArrayList<>();
                try {
                    if (!java.util.Set.of("enabled", "admissionEnabled", "maxEstimatedWait", "maxQueueWait",
                            "retryAfterSeconds").containsAll(patch.keySet())) {
                        throw new IllegalArgumentException("unknown field");
                    }
                    Map<String, Object> candidate = new java.util.HashMap<>(source.snapshot());
                    candidate.putAll(patch);
                    if (!(candidate.get("enabled") instanceof Boolean)
                            || !(candidate.get("admissionEnabled") instanceof Boolean)
                            || !(candidate.get("maxEstimatedWait") instanceof String)
                            || !(candidate.get("maxQueueWait") instanceof String)
                            || !isPositiveInt(candidate.get("retryAfterSeconds"))
                            || !java.time.Duration.parse((String) candidate.get("maxEstimatedWait")).isPositive()
                            || !java.time.Duration.parse((String) candidate.get("maxQueueWait")).isPositive()
                            || java.time.Duration.parse((String) candidate.get("maxEstimatedWait"))
                            .compareTo(java.time.Duration.parse((String) candidate.get("maxQueueWait"))) > 0) {
                        throw new IllegalArgumentException("invalid type or non-positive value");
                    }
                } catch (RuntimeException e) {
                    errors.add("Invalid sync-queue values: " + e.getMessage());
                }
                return errors;
            }
            @Override public void apply(Map<String, Object> patch) { source.apply(patch); }
            @Override public void restore(Map<String, Object> snapshot) { source.restore(snapshot); }
        };
    }

    private boolean isPositiveInt(Object value) {
        if (!(value instanceof Number number)) {
            return false;
        }
        try {
            return new BigDecimal(number.toString()).intValueExact() > 0;
        } catch (ArithmeticException | NumberFormatException e) {
            return false;
        }
    }
}
