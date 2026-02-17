package it.unimib.datai.nanofaas.controlplane.config.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeConfigValidatorTest {

    private final RuntimeConfigValidator validator = new RuntimeConfigValidator();

    @Test
    void acceptsValidPatch() {
        RuntimeConfigPatch patch = new RuntimeConfigPatch(500, true, false, Duration.ofSeconds(5), Duration.ofSeconds(3), 2);
        List<String> errors = validator.validate(patch);
        assertTrue(errors.isEmpty());
    }

    @Test
    void acceptsAllNullPatch() {
        RuntimeConfigPatch patch = new RuntimeConfigPatch(null, null, null, null, null, null);
        assertTrue(validator.validate(patch).isEmpty());
    }

    @Test
    void rejectsZeroRateMaxPerSecond() {
        RuntimeConfigPatch patch = new RuntimeConfigPatch(0, null, null, null, null, null);
        List<String> errors = validator.validate(patch);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("rateMaxPerSecond"));
    }

    @Test
    void rejectsNegativeRateMaxPerSecond() {
        RuntimeConfigPatch patch = new RuntimeConfigPatch(-1, null, null, null, null, null);
        assertEquals(1, validator.validate(patch).size());
    }

    @Test
    void rejectsZeroDurationMaxEstimatedWait() {
        RuntimeConfigPatch patch = new RuntimeConfigPatch(null, null, null, Duration.ZERO, null, null);
        List<String> errors = validator.validate(patch);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("syncQueueMaxEstimatedWait"));
    }

    @Test
    void rejectsNegativeDurationMaxQueueWait() {
        RuntimeConfigPatch patch = new RuntimeConfigPatch(null, null, null, null, Duration.ofSeconds(-1), null);
        assertEquals(1, validator.validate(patch).size());
    }

    @Test
    void rejectsZeroRetryAfterSeconds() {
        RuntimeConfigPatch patch = new RuntimeConfigPatch(null, null, null, null, null, 0);
        List<String> errors = validator.validate(patch);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("syncQueueRetryAfterSeconds"));
    }

    @Test
    void collectsMultipleErrors() {
        RuntimeConfigPatch patch = new RuntimeConfigPatch(-1, null, null, Duration.ZERO, Duration.ofSeconds(-1), 0);
        List<String> errors = validator.validate(patch);
        assertEquals(4, errors.size());
    }
}
