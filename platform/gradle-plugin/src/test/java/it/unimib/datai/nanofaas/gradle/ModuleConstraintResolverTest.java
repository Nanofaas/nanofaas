package it.unimib.datai.nanofaas.gradle;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModuleConstraintResolverTest {

    private final ModuleConstraintResolver resolver = new ModuleConstraintResolver();

    @Test
    void acceptsStrongAndWeakRequirementsWhenSatisfied() {
        ModuleDescriptor base = descriptor("base", true, List.of(), List.of(), List.of());
        ModuleDescriptor missing = descriptor("missing", false, List.of(), List.of(), List.of());
        ModuleDescriptor optional = descriptor("optional", true, List.of("base"), List.of("missing"), List.of());

        assertThatCode(() -> resolver.validate(List.of(base, missing, optional), List.of("optional", "base")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsDuplicatesUnknownReferencesMissingStrongAndConflicts() {
        ModuleDescriptor first = descriptor("first", true, List.of("missing"), List.of(), List.of("second"));
        ModuleDescriptor second = descriptor("second", true, List.of(), List.of(), List.of());

        assertThatThrownBy(() -> resolver.validate(List.of(first, first), List.of("first")))
                .hasMessageContaining("first").hasMessageContaining("duplicate");
        assertThatThrownBy(() -> resolver.validate(List.of(first), List.of("unknown")))
                .hasMessageContaining("unknown");
        assertThatThrownBy(() -> resolver.validate(List.of(first), List.of("first")))
                .hasMessageContaining("first").hasMessageContaining("missing");
        assertThatThrownBy(() -> resolver.validate(List.of(
                        descriptor("first", true, List.of(), List.of(), List.of("second")), second),
                        List.of("first", "second")))
                .hasMessageContaining("first").hasMessageContaining("second");
    }

    @Test
    void doesNotMutateSelectedModules() {
        ModuleDescriptor descriptor = descriptor("module", true, List.of(), List.of(), List.of());
        List<String> selected = new java.util.ArrayList<>(List.of("module"));

        resolver.validate(List.of(descriptor), selected);

        org.assertj.core.api.Assertions.assertThat(selected).containsExactly("module");
    }

    @Test
    void enforcesTransitiveStrongRequirementsAndTerminatesOnCycles() {
        ModuleDescriptor a = descriptor("a", true, List.of("b"), List.of(), List.of());
        ModuleDescriptor b = descriptor("b", true, List.of("c"), List.of(), List.of());
        ModuleDescriptor c = descriptor("c", true, List.of(), List.of(), List.of());

        assertThatThrownBy(() -> resolver.validate(List.of(a, b, c), List.of("a", "b")))
                .hasMessageContaining("module 'a'").hasMessageContaining("c");

        ModuleDescriptor cycleA = descriptor("cycle-a", true, List.of("cycle-b"), List.of(), List.of());
        ModuleDescriptor cycleB = descriptor("cycle-b", true, List.of("cycle-a"), List.of(), List.of());
        resolver.validate(List.of(cycleA, cycleB), List.of("cycle-a", "cycle-b"));
    }

    private ModuleDescriptor descriptor(String id, boolean enabled, List<String> strong,
                                       List<String> weak, List<String> conflicts) {
        return new ModuleDescriptor(1, id, enabled, strong, weak, conflicts);
    }
}
