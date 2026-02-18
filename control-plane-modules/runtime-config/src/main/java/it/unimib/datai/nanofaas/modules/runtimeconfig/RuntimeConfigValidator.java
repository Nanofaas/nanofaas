package it.unimib.datai.nanofaas.modules.runtimeconfig;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates a {@link RuntimeConfigPatch} before it is applied.
 * Returns a list of human-readable validation errors (empty = valid).
 */
@Component
public class RuntimeConfigValidator {

    public List<String> validate(RuntimeConfigPatch patch) {
        List<String> errors = new ArrayList<>();

        if (patch.rateMaxPerSecond() != null && patch.rateMaxPerSecond() <= 0) {
            errors.add("rateMaxPerSecond must be > 0, got " + patch.rateMaxPerSecond());
        }
        if (patch.syncQueueMaxEstimatedWait() != null && !isPositiveDuration(patch.syncQueueMaxEstimatedWait())) {
            errors.add("syncQueueMaxEstimatedWait must be > 0");
        }
        if (patch.syncQueueMaxQueueWait() != null && !isPositiveDuration(patch.syncQueueMaxQueueWait())) {
            errors.add("syncQueueMaxQueueWait must be > 0");
        }
        if (patch.syncQueueRetryAfterSeconds() != null && patch.syncQueueRetryAfterSeconds() < 1) {
            errors.add("syncQueueRetryAfterSeconds must be >= 1, got " + patch.syncQueueRetryAfterSeconds());
        }

        return errors;
    }

    private static boolean isPositiveDuration(Duration d) {
        return d != null && !d.isZero() && !d.isNegative();
    }
}
