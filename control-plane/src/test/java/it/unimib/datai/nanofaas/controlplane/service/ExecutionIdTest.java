package it.unimib.datai.nanofaas.controlplane.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionIdTest {
    private static final Pattern UUID_V4 = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    @Test
    void executionIdsAreValidV4UuidsAndUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            String id = InvocationExecutionFactory.newExecutionId();
            assertThat(id).matches(UUID_V4);
            assertThat(seen.add(id)).isTrue();
        }
    }
}
