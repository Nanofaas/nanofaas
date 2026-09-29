package it.unimib.datai.nanofaas.controlplane.execution;

import it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionStoreBindingTest {
    @Test
    void absentPropertiesKeepRuntimeDefaults() {
        var actual = new ExecutionExpiryConfiguration()
                .executionStoreProperties(new MockEnvironment());
        assertThat(actual).isEqualTo(new ExecutionStoreProperties(null, null, null, 0, 0, 0));
    }

    @Test
    void partialPropertiesBindDurationsAndRetainNormalization() {
        var env = new MockEnvironment()
                .withProperty("nanofaas.execution-store.ttl", "10s")
                .withProperty("nanofaas.execution-store.sync-ttl", "20s")
                .withProperty("nanofaas.execution-store.max-outcomes", "7")
                .withProperty("nanofaas.execution-store.max-keys", "9");
        var actual = new ExecutionExpiryConfiguration().executionStoreProperties(env);
        assertThat(actual.ttl()).isEqualTo(Duration.ofSeconds(10));
        assertThat(actual.syncTtl()).isEqualTo(Duration.ofSeconds(10));
        assertThat(actual.maxLifetime()).isEqualTo(Duration.ofMinutes(30));
        assertThat(actual.maxOutcomes()).isEqualTo(7);
        assertThat(actual.maxKeys()).isEqualTo(9);
        assertThat(actual.maxOutcomeBytes()).isEqualTo(7 * ExecutionStoreProperties.COMPACT_OUTCOME_BYTES);
    }

    @Test
    void explicitByteBudgetOverridesDerivedBudget() {
        var env = new MockEnvironment()
                .withProperty("nanofaas.execution-store.max-outcomes", "7")
                .withProperty("nanofaas.execution-store.max-outcome-bytes", "4096");
        assertThat(new ExecutionExpiryConfiguration().executionStoreProperties(env)
                .maxOutcomeBytes()).isEqualTo(4096);
    }

    @Test
    void infoReportsNormalizedRetention() {
        var configuration = new ExecutionExpiryConfiguration();
        var properties = configuration.executionStoreProperties(new MockEnvironment()
                .withProperty("nanofaas.execution-store.ttl", "10s")
                .withProperty("nanofaas.execution-store.sync-ttl", "20s"));
        var builder = new Info.Builder();

        configuration.executionStoreInfoContributor(properties).contribute(builder);

        assertThat(builder.build().getDetails()).containsEntry("executionStore", Map.of(
                "ttl", "PT10S",
                "syncTtl", "PT10S",
                "maxLifetime", "PT30M"));
    }
}
