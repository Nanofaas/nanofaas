package it.unimib.datai.nanofaas.gradle;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModuleDescriptorTest {

    @Test
    void rejectsInvalidDirectConstruction() {
        assertThatThrownBy(() -> descriptor(0, "module", List.of(), List.of(), List.of()))
                .hasMessageContaining("schemaVersion");
        assertThatThrownBy(() -> descriptor(1, " ", List.of(), List.of(), List.of()))
                .hasMessageContaining("id");
        assertThatThrownBy(() -> descriptor(1, "module", List.of(" "), List.of(), List.of()))
                .hasMessageContaining("blank");
        assertThatThrownBy(() -> descriptor(1, "module", List.of("other", "other"), List.of(), List.of()))
                .hasMessageContaining("duplicate");
    }

    @Test
    void rejectsSelfReferencesInEveryConstraintList() {
        assertThatThrownBy(() -> descriptor(1, "module", List.of("module"), List.of(), List.of()))
                .hasMessageContaining("itself");
        assertThatThrownBy(() -> descriptor(1, "module", List.of(), List.of("module"), List.of()))
                .hasMessageContaining("itself");
        assertThatThrownBy(() -> descriptor(1, "module", List.of(), List.of(), List.of("module")))
                .hasMessageContaining("itself");
        assertThatThrownBy(() -> descriptor(1, "module", List.of(), List.of(), List.of(), List.of("module")))
                .hasMessageContaining("itself");
    }

    private ModuleDescriptor descriptor(int schemaVersion, String id, List<String> strong,
                                       List<String> weak, List<String> conflicts) {
        return descriptor(schemaVersion, id, strong, weak, conflicts, List.of());
    }

    private ModuleDescriptor descriptor(int schemaVersion, String id, List<String> strong,
                                       List<String> weak, List<String> conflicts, List<String> oneOf) {
        return new ModuleDescriptor(schemaVersion, id, true, strong, weak, oneOf, conflicts);
    }
}
