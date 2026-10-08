package it.unimib.datai.nanofaas.gradle;

import java.util.List;
import java.util.HashSet;
import java.util.Set;

public record ModuleDescriptor(
        int schemaVersion,
        String id,
        boolean defaultEnabled,
        List<String> strongRequirements,
        List<String> weakRequirements,
        List<String> oneOfRequirements,
        List<String> conflicts) {

    public ModuleDescriptor {
        if (schemaVersion <= 0) {
            throw invalid("schemaVersion must be positive");
        }
        if (id == null || id.isBlank()) {
            throw invalid("id must not be blank");
        }
        strongRequirements = validateList(strongRequirements, "strongRequirements", id);
        weakRequirements = validateList(weakRequirements, "weakRequirements", id);
        oneOfRequirements = validateList(oneOfRequirements, "oneOfRequirements", id);
        conflicts = validateList(conflicts, "conflicts", id);
    }

    private static List<String> validateList(List<String> values, String name, String id) {
        if (values == null) {
            throw invalid(name + " must not be null");
        }
        Set<String> seen = new HashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw invalid(name + " contains a blank reference");
            }
            if (!seen.add(value)) {
                throw invalid("duplicate reference '" + value + "' in " + name);
            }
            if (value.equals(id)) {
                throw invalid("module '" + id + "' cannot reference itself");
            }
        }
        return List.copyOf(values);
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid module descriptor: " + message);
    }
}
