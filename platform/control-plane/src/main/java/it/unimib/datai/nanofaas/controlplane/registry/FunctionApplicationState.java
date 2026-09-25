package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentTarget;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Volatile application progress for durable function records.
 *
 * <p>There is at most one entry per name in the current durable registry snapshot and at most one
 * PATCH, scale and unavailable marker in that entry. Strings reported by providers are bounded;
 * complete recovery identifiers remain in the durable {@link RegisteredFunction}. A new registry
 * starts empty and startup restore reconstructs only unavailable markers for failed reconciles.
 */
final class FunctionApplicationState {
    private static final int MAX_REPORTED_RESOURCES = 64;
    private static final int MAX_REASON_LENGTH = 512;

    private Set<String> retainedNames;
    private final Map<String, Applications> byFunction = new HashMap<>();

    FunctionApplicationState(Set<String> durableNames) {
        this.retainedNames = Set.copyOf(durableNames);
    }

    synchronized void markUpdate(RegisteredFunction function) {
        mutateDurable(function.name(), current -> current.withUpdate(function));
    }

    synchronized boolean isUpdatePending(RegisteredFunction function) {
        Applications current = byFunction.get(function.name());
        if (current == null || current.update() == null) {
            return false;
        }
        if (current.update().equals(function)) {
            return true;
        }
        store(function.name(), current.withUpdate(null));
        return false;
    }

    synchronized void completeUpdate(RegisteredFunction function) {
        Applications current = byFunction.get(function.name());
        if (current != null && function.equals(current.update())) {
            store(function.name(), current.withUpdate(null));
        }
    }

    synchronized void markScale(ManagedDeploymentTarget target, int replicas) {
        mutateDurable(target.functionName(), current -> current.withScale(new Scale(target, replicas)));
    }

    synchronized boolean isScalePending(ManagedDeploymentTarget target, int replicas) {
        Applications current = byFunction.get(target.functionName());
        if (current == null || current.scale() == null) {
            return false;
        }
        Scale expected = new Scale(target, replicas);
        if (expected.equals(current.scale())) {
            return true;
        }
        store(target.functionName(), current.withScale(null));
        return false;
    }

    synchronized void completeScale(ManagedDeploymentTarget target, int replicas) {
        Applications current = byFunction.get(target.functionName());
        if (current != null && new Scale(target, replicas).equals(current.scale())) {
            store(target.functionName(), current.withScale(null));
        }
    }

    synchronized boolean beginRemoval(String functionName) {
        return isUnavailable(functionName);
    }

    synchronized void markPartialRemoval(String functionName, List<String> remainingResources) {
        List<String> bounded = remainingResources == null ? List.of() : remainingResources.stream()
                .limit(MAX_REPORTED_RESOURCES)
                .map(FunctionApplicationState::boundedText)
                .toList();
        mutateDurable(functionName, current -> current.withUnavailable(new Unavailable(bounded, null)));
    }

    synchronized void markUnavailable(String functionName, String reason) {
        mutateDurable(functionName,
                current -> current.withUnavailable(new Unavailable(null, boundedText(reason))));
    }

    synchronized void requireAvailable(String functionName) {
        Applications current = byFunction.get(functionName);
        if (current == null || current.unavailable() == null) {
            return;
        }
        Unavailable unavailable = current.unavailable();
        if (unavailable.remainingResources() != null) {
            throw new FunctionRemovalPendingException(functionName, unavailable.remainingResources());
        }
        throw new FunctionApplicationPendingException(functionName, unavailable.reason());
    }

    synchronized boolean isUnavailable(String functionName) {
        Applications current = byFunction.get(functionName);
        return current != null && current.unavailable() != null;
    }

    synchronized void clearFunction(String functionName) {
        byFunction.remove(functionName);
    }

    synchronized void clearScaleApplications() {
        byFunction.replaceAll((ignored, current) -> current.withScale(null));
        byFunction.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    synchronized void clearAll() {
        byFunction.clear();
    }

    synchronized void retainOnly(Set<String> names) {
        retainedNames = Set.copyOf(names);
        byFunction.keySet().retainAll(names);
    }

    synchronized void retainName(String name) {
        java.util.HashSet<String> next = new java.util.HashSet<>(retainedNames);
        next.add(name);
        retainedNames = Set.copyOf(next);
    }

    synchronized int retainedFunctionCount() {
        return byFunction.size();
    }

    synchronized int retainedMarkerCount() {
        return byFunction.values().stream().mapToInt(Applications::markerCount).sum();
    }

    private void mutateDurable(String functionName,
                               java.util.function.UnaryOperator<Applications> mutation) {
        if (!retainedNames.contains(functionName)) {
            throw new IllegalStateException("Cannot retain application state for non-durable function '"
                    + functionName + "'");
        }
        Applications current = byFunction.getOrDefault(functionName, Applications.EMPTY);
        store(functionName, mutation.apply(current));
    }

    private void store(String functionName, Applications applications) {
        if (applications.isEmpty()) {
            byFunction.remove(functionName);
        } else {
            byFunction.put(functionName, applications);
        }
    }

    private static String boundedText(String value) {
        String text = value == null || value.isBlank() ? "provider state could not be verified" : value;
        return text.length() <= MAX_REASON_LENGTH ? text : text.substring(0, MAX_REASON_LENGTH);
    }

    private record Scale(ManagedDeploymentTarget target, int replicas) {
    }

    private record Unavailable(List<String> remainingResources, String reason) {
    }

    private record Applications(RegisteredFunction update, Scale scale, Unavailable unavailable) {
        private static final Applications EMPTY = new Applications(null, null, null);

        Applications withUpdate(RegisteredFunction value) {
            return new Applications(value, scale, unavailable);
        }

        Applications withScale(Scale value) {
            return new Applications(update, value, unavailable);
        }

        Applications withUnavailable(Unavailable value) {
            return new Applications(update, scale, value);
        }

        boolean isEmpty() {
            return update == null && scale == null && unavailable == null;
        }

        int markerCount() {
            return (update == null ? 0 : 1) + (scale == null ? 0 : 1) + (unavailable == null ? 0 : 1);
        }

    }
}
