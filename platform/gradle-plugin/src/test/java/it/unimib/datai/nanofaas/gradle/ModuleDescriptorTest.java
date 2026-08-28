package it.unimib.datai.nanofaas.gradle;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModuleDescriptorTest {

    @Test
    void rejectsInvalidDirectConstruction() {
        assertThatThrownBy(() -> descriptor(0, "module", List.of(), List.of(), List.of(), List.of()))
                .hasMessageContaining("schemaVersion");
        assertThatThrownBy(() -> descriptor(1, " ", List.of(), List.of(), List.of(), List.of()))
                .hasMessageContaining("id");
        assertThatThrownBy(() -> descriptor(1, "module", List.of(" "), List.of(), List.of(), List.of()))
                .hasMessageContaining("blank");
        assertThatThrownBy(() -> descriptor(1, "module", List.of("other", "other"), List.of(), List.of(), List.of()))
                .hasMessageContaining("duplicate");
    }

    @Test
    void rejectsSelfReferencesInEveryConstraintList() {
        assertThatThrownBy(() -> descriptor(1, "module", List.of("module"), List.of(), List.of(), List.of()))
                .hasMessageContaining("itself");
        assertThatThrownBy(() -> descriptor(1, "module", List.of(), List.of("module"), List.of(), List.of()))
                .hasMessageContaining("itself");
        assertThatThrownBy(() -> descriptor(1, "module", List.of(), List.of(), List.of("module"), List.of()))
                .hasMessageContaining("itself");
        assertThatThrownBy(() -> descriptor(1, "module", List.of(), List.of(), List.of("module"), List.of()))
                .hasMessageContaining("itself");
    }

    @Test
    void keepsOneOfRequirementsImmutable() {
        List<String> oneOf = new java.util.ArrayList<>(List.of("provider"));
        ModuleDescriptor descriptor = descriptor(1, "consumer", List.of(), List.of(), oneOf, List.of());
        oneOf.add("another-provider");

        assertThat(descriptor.oneOfRequirements()).containsExactly("provider");
        assertThatThrownBy(() -> descriptor.oneOfRequirements().add("another-provider"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private ModuleDescriptor descriptor(int schemaVersion, String id, List<String> strong,
                                       List<String> weak, List<String> oneOf, List<String> conflicts) {
        return new ModuleDescriptor(schemaVersion, id, true, strong, weak, oneOf, conflicts);
    }
}
