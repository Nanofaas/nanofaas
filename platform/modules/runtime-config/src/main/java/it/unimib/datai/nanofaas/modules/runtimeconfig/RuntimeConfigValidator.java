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
        return validate(
                patch.rateMaxPerSecond(),
                patch.syncQueueMaxEstimatedWait(),
                patch.syncQueueMaxQueueWait(),
                patch.syncQueueRetryAfterSeconds()
        );
    }

    public List<String> validate(RuntimeConfigSnapshot snapshot) {
        return validate(
                snapshot.rateMaxPerSecond(),
                snapshot.syncQueueMaxEstimatedWait(),
                snapshot.syncQueueMaxQueueWait(),
                snapshot.syncQueueRetryAfterSeconds()
        );
    }

    private List<String> validate(Integer rateMaxPerSecond,
                                  Duration syncQueueMaxEstimatedWait,
                                  Duration syncQueueMaxQueueWait,
                                  Integer syncQueueRetryAfterSeconds) {
        List<String> errors = new ArrayList<>();
        boolean estimatedWaitValid = syncQueueMaxEstimatedWait == null || isPositiveDuration(syncQueueMaxEstimatedWait);
        boolean queueWaitValid = syncQueueMaxQueueWait == null || isPositiveDuration(syncQueueMaxQueueWait);

        if (rateMaxPerSecond != null && rateMaxPerSecond <= 0) {
            errors.add("rateMaxPerSecond must be > 0, got " + rateMaxPerSecond);
        }
        if (!estimatedWaitValid) {
            errors.add("syncQueueMaxEstimatedWait must be > 0");
        }
        if (!queueWaitValid) {
            errors.add("syncQueueMaxQueueWait must be > 0");
        }
        if (estimatedWaitValid
                && queueWaitValid
                && syncQueueMaxEstimatedWait != null
                && syncQueueMaxQueueWait != null
                && syncQueueMaxEstimatedWait.compareTo(syncQueueMaxQueueWait) > 0) {
            errors.add("syncQueueMaxEstimatedWait must be <= syncQueueMaxQueueWait");
        }
        if (syncQueueRetryAfterSeconds != null && syncQueueRetryAfterSeconds < 1) {
            errors.add("syncQueueRetryAfterSeconds must be >= 1, got " + syncQueueRetryAfterSeconds);
        }

        return errors;
    }

    private static boolean isPositiveDuration(Duration d) {
        return d != null && !d.isZero() && !d.isNegative();
    }
}
