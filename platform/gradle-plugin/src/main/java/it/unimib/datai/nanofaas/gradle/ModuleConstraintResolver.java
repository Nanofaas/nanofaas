package it.unimib.datai.nanofaas.gradle;

import java.util.Collection;
import java.util.ArrayDeque;
import java.util.Deque;
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
            validateReferences(descriptor, descriptor.conflicts(), "conflict", byId);
        }

        validateStrongRequirements(byId, selected);

        for (String selectedId : selected) {
            ModuleDescriptor descriptor = byId.get(selectedId);
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
        for (String rootId : selected) {
            Deque<String> pending = new ArrayDeque<>(byId.get(rootId).strongRequirements());
            Set<String> visited = new HashSet<>();
            while (!pending.isEmpty()) {
                String requirement = pending.removeFirst();
                if (!selected.contains(requirement)) {
                    throw invalid("module '" + rootId + "' requires strong module '" + requirement + "'");
                }
                if (visited.add(requirement)) {
                    pending.addAll(byId.get(requirement).strongRequirements());
                }
            }
        }
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
