package it.unimib.datai.nanofaas.modules.runtimeconfig;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeConfigRegistryTest {

    @Test
    void ordersAndExposesImmutableNamespaces() {
        RuntimeConfigRegistry registry = new RuntimeConfigRegistry(List.of(
                extension("sync-queue", 2), extension("control-plane", 1)));

        assertThat(registry.snapshot()).containsKeys("control-plane", "sync-queue")
                .extracting(Map::keySet)
                .isEqualTo(java.util.Set.of("control-plane", "sync-queue"));
        assertThat(registry.extension("control-plane")).isPresent();
        assertThatThrownBy(() -> registry.snapshot().put("other", Map.of()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsBlankAndDuplicateNamespaces() {
        assertThatThrownBy(() -> new RuntimeConfigRegistry(List.of(extension(" ", 1))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new RuntimeConfigRegistry(List.of(
                extension("queue", 1), extension("queue", 2))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("queue");
    }

    private static RuntimeConfigExtension extension(String namespace, int value) {
        return new RuntimeConfigExtension() {
            @Override
            public String namespace() {
                return namespace;
            }

            @Override
            public Map<String, Object> snapshot() {
                return Map.of("value", value);
            }

            @Override
            public List<String> validate(Map<String, Object> patch) {
                return List.of();
            }

            @Override
            public void apply(Map<String, Object> patch) {
                // The registry is what is under test here; this stub records nothing.
            }

            @Override
            public void restore(Map<String, Object> snapshot) {
                // Same: rollback is exercised through the service tests, not here.
            }
        };
    }
}
