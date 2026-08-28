package it.unimib.datai.nanofaas.gradle;

import java.util.List;
import java.util.Objects;

public record ModuleDescriptor(
        int schemaVersion,
        String id,
        boolean defaultEnabled,
        List<String> strongRequirements,
        List<String> weakRequirements,
        List<String> conflicts) {

    public ModuleDescriptor {
        id = Objects.requireNonNull(id, "id");
        strongRequirements = List.copyOf(Objects.requireNonNull(strongRequirements, "strongRequirements"));
        weakRequirements = List.copyOf(Objects.requireNonNull(weakRequirements, "weakRequirements"));
        conflicts = List.copyOf(Objects.requireNonNull(conflicts, "conflicts"));
    }
}
