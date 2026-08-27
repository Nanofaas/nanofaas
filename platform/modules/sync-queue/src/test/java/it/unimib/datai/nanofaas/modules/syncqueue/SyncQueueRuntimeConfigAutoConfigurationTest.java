package it.unimib.datai.nanofaas.modules.syncqueue;

import it.unimib.datai.nanofaas.modules.runtimeconfig.RuntimeConfigExtension;
import it.unimib.datai.nanofaas.modules.syncqueue.config.SyncQueueProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

class SyncQueueRuntimeConfigAutoConfigurationTest {
    @Test
    void acceptsOnlyPositiveIntRetryAfterValues() {
        SyncQueueProperties properties = new SyncQueueProperties(true, true, 100,
                Duration.ofSeconds(1), Duration.ofSeconds(2), 1, Duration.ofMinutes(1), 1);
        MutableSyncQueueConfigSource source = new MutableSyncQueueConfigSource(properties);
        RuntimeConfigExtension extension = new SyncQueueRuntimeConfigAutoConfiguration()
                .syncQueueRuntimeConfigExtension(source);

        assertSoftly(softly -> {
            for (Number value : new Number[]{1.5, 2147483648L}) {
                softly.assertThat(extension.validate(Map.of("retryAfterSeconds", value)))
                        .as("value %s", value)
                        .isNotEmpty();
            }
            softly.assertThat(extension.validate(Map.of("retryAfterSeconds", Integer.MAX_VALUE))).isEmpty();
        });
    }
}
