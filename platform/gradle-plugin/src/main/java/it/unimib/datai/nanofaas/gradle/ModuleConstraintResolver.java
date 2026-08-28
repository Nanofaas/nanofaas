package it.unimib.datai.nanofaas.gradle;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class ModuleConstraintResolver {

    public void validate(Collection<ModuleDescriptor> descriptors, Collection<String> selectedIds) {
        Map<String, ModuleDescriptor> byId = new HashMap<>();
        for (ModuleDescriptor descriptor : descriptors) {
            if (byId.putIfAbsent(descriptor.id(), descriptor) != null) {
                throw invalid("duplicate module id '" + descriptor.id() + "'");
            }
        }

        Set<String> selected = new HashSet<>();
        for (String selectedId : selectedIds) {
            if (!selected.add(selectedId)) {
                throw invalid("duplicate selected module id '" + selectedId + "'");
            }
            if (!byId.containsKey(selectedId)) {
                throw invalid("unknown selected module '" + selectedId + "'");
            }
        }

        for (ModuleDescriptor descriptor : descriptors) {
            validateReferences(descriptor, descriptor.strongRequirements(), "strong requirement", byId);
            validateReferences(descriptor, descriptor.weakRequirements(), "weak requirement", byId);
            validateReferences(descriptor, descriptor.oneOfRequirements(), "oneOf requirement", byId);
            validateReferences(descriptor, descriptor.conflicts(), "conflict", byId);
        }

        validateStrongRequirements(byId, selected);

        for (String selectedId : selected) {
            ModuleDescriptor descriptor = byId.get(selectedId);
            if (!descriptor.oneOfRequirements().isEmpty()
                    && descriptor.oneOfRequirements().stream().noneMatch(selected::contains)) {
                throw invalid("module '" + selectedId + "' requires at least one oneOf module: "
                        + descriptor.oneOfRequirements());
            }
            for (String conflict : descriptor.conflicts()) {
                if (selected.contains(conflict)) {
                    throw invalid("modules '" + selectedId + "' and '" + conflict + "' conflict");
                }
            }
            for (String otherId : selected) {
                ModuleDescriptor other = byId.get(otherId);
                if (other.conflicts().contains(selectedId)) {
                    throw invalid("modules '" + selectedId + "' and '" + otherId + "' conflict");
                }
            }
        }
    }

    private static void validateStrongRequirements(Map<String, ModuleDescriptor> byId, Set<String> selected) {
        Set<String> active = new HashSet<>();
        Set<String> validated = new HashSet<>();
        for (String rootId : selected) {
            validateStrongRequirements(rootId, rootId, byId, selected, active, validated);
        }
    }

    private static void validateStrongRequirements(String rootId, String moduleId,
                                                   Map<String, ModuleDescriptor> byId, Set<String> selected,
                                                   Set<String> active, Set<String> validated) {
        if (validated.contains(moduleId)) {
            return;
        }
        active.add(moduleId);
        for (String requirement : byId.get(moduleId).strongRequirements()) {
            if (!selected.contains(requirement)) {
                throw invalid("module '" + rootId + "' requires strong module '" + requirement + "'");
            }
            if (active.contains(requirement)) {
                throw invalid("strong requirement cycle: module '" + moduleId
                        + "' requires active module '" + requirement + "'");
            }
            validateStrongRequirements(rootId, requirement, byId, selected, active, validated);
        }
        active.remove(moduleId);
        validated.add(moduleId);
    }

    private static void validateReferences(ModuleDescriptor descriptor, Collection<String> references,
                                            String kind, Map<String, ModuleDescriptor> byId) {
        for (String reference : references) {
            if (!byId.containsKey(reference)) {
                throw invalid("module '" + descriptor.id() + "' has unknown " + kind + " '" + reference + "'");
            }
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid module constraints: " + message);
    }
}
