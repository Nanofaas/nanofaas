package it.unimib.datai.nanofaas.modules.runtimeconfig;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeConfigServiceTest {
    @Test
    void updatesOneNamespaceAndIncrementsRevision() {
        TestExtension extension = new TestExtension("queue", 1);
        RuntimeConfigService service = service(extension);

        RuntimeConfigSnapshot updated = service.update(0, "queue", Map.of("value", 2));

        assertThat(updated.revision()).isOne();
        assertThat(updated.namespaces().get("queue")).containsEntry("value", 2);
    }

    @Test
    void staleAndUnknownUpdatesHaveNoSideEffects() {
        TestExtension extension = new TestExtension("queue", 1);
        RuntimeConfigService service = service(extension);

        assertThatThrownBy(() -> service.update(1, "queue", Map.of("value", 2)))
                .isInstanceOf(RevisionMismatchException.class);
        assertThatThrownBy(() -> service.update(0, "missing", Map.of("value", 2)))
                .isInstanceOf(UnknownRuntimeConfigNamespaceException.class);
        assertThat(extension.value).isEqualTo(1);
        assertThat(service.getSnapshot().revision()).isZero();
    }

    @Test
    void failedApplyRestoresPreviousStateAndRevision() {
        TestExtension extension = new TestExtension("queue", 1);
        extension.fail = true;
        RuntimeConfigService service = service(extension);

        assertThatThrownBy(() -> service.update(0, "queue", Map.of("value", 2)))
                .isInstanceOf(RuntimeConfigApplyException.class);
        assertThat(extension.value).isEqualTo(1);
        assertThat(service.getSnapshot().revision()).isZero();
    }

    private static RuntimeConfigService service(TestExtension extension) {
        return new RuntimeConfigService(new RuntimeConfigRegistry(List.of(extension)), new SimpleMeterRegistry());
    }

    private static final class TestExtension implements RuntimeConfigExtension {
        private final String namespace;
        private int value;
        private boolean fail;

        private TestExtension(String namespace, int value) {
            this.namespace = namespace;
            this.value = value;
        }

        @Override public String namespace() { return namespace; }
        @Override public Map<String, Object> snapshot() { return Map.of("value", value); }
        @Override public List<String> validate(Map<String, Object> patch) { return List.of(); }
        @Override public void apply(Map<String, Object> patch) {
            value = ((Number) patch.get("value")).intValue();
            if (fail) throw new IllegalStateException("boom");
        }
        @Override public void restore(Map<String, Object> snapshot) { value = ((Number) snapshot.get("value")).intValue(); }
    }
}
