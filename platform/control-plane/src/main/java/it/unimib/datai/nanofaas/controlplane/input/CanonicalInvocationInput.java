package it.unimib.datai.nanofaas.controlplane.input;

import it.unimib.datai.nanofaas.common.model.InvocationRequest;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Converts parser-owned JSON collections into an explicitly bounded array tree.
 * The source collection is never retained by a successful result. Arbitrary collection
 * implementations are rejected before invoking any of their methods.
 */
public final class CanonicalInvocationInput {
    private static final String LIST = new String("nanofaas:list");
    private static final String MAP = new String("nanofaas:map");

    private CanonicalInvocationInput() {
    }

    public static Result canonicalize(
            InvocationRequest request, RetainedInputEstimator.Limits limits) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(limits, "limits");
        RetainedInputEstimator estimator = new RetainedInputEstimator(limits);
        RetainedInputEstimator.Result direct = estimator.estimate(request.input());
        if (direct instanceof RetainedInputEstimator.Measured measured) {
            return new Accepted(request, measured.retainedBytes(), false);
        }

        Builder builder = new Builder(limits);
        BuildResult built = builder.build(request.input(), 0);
        if (built.rejection != null) {
            return new Rejected(built.rejection);
        }
        RetainedInputEstimator.Result estimate = estimator.estimate(built.value);
        if (estimate instanceof RetainedInputEstimator.Rejected rejected) {
            return new Rejected(rejected.reason());
        }
        long retainedBytes = ((RetainedInputEstimator.Measured) estimate).retainedBytes();
        InvocationRequest canonical = new InvocationRequest(
                built.value, request.metadata(), request.headers());
        return new Accepted(canonical, retainedBytes, true);
    }

    public sealed interface Result permits Accepted, Rejected {
    }

    public record Accepted(
            InvocationRequest canonicalRequest, long retainedBytes, boolean requiresMaterialization)
            implements Result {
        public Accepted {
            Objects.requireNonNull(canonicalRequest, "canonicalRequest");
            if (retainedBytes < 0) {
                throw new IllegalArgumentException("retainedBytes must not be negative");
            }
        }

        /** Creates the JSON-compatible physical representation used by one dispatch attempt. */
        public InvocationRequest materializeRequest() {
            if (!requiresMaterialization) {
                return canonicalRequest;
            }
            return new InvocationRequest(
                    materialize(canonicalRequest.input()),
                    canonicalRequest.metadata(),
                    canonicalRequest.headers());
        }
    }

    public record Rejected(RetainedInputEstimator.Rejection reason) implements Result {
        public Rejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    private static Object materialize(Object value) {
        if (!(value instanceof Object[] array)) {
            return value;
        }
        if (array.length > 0 && array[0] == LIST) {
            List<Object> list = new ArrayList<>(array.length - 1);
            for (int index = 1; index < array.length; index++) {
                list.add(materialize(array[index]));
            }
            return list;
        }
        if (array.length > 0 && array[0] == MAP) {
            Map<String, Object> map = LinkedHashMap.newLinkedHashMap((array.length - 1) / 2);
            for (int index = 1; index < array.length; index += 2) {
                map.put((String) array[index], materialize(array[index + 1]));
            }
            return map;
        }
        Object[] copy = new Object[array.length];
        for (int index = 0; index < array.length; index++) {
            copy[index] = materialize(array[index]);
        }
        return copy;
    }

    private static final class Builder {
        private final RetainedInputEstimator.Limits limits;
        private final IdentityHashMap<Object, Boolean> visiting = new IdentityHashMap<>();
        private int nodes;

        private Builder(RetainedInputEstimator.Limits limits) {
            this.limits = limits;
        }

        private BuildResult build(Object value, int depth) {
            if (depth > limits.maxDepth()) {
                return BuildResult.rejected(RetainedInputEstimator.Rejection.DEPTH_LIMIT);
            }
            if (++nodes > limits.maxVisitedNodes()) {
                return BuildResult.rejected(RetainedInputEstimator.Rejection.NODE_LIMIT);
            }
            if (value == null || value instanceof String || value instanceof Boolean
                    || value instanceof Number || value instanceof Character) {
                return BuildResult.accepted(value);
            }
            Class<?> type = value.getClass();
            if (type.isArray()) {
                return buildArray(value, type.getComponentType(), depth);
            }
            if (isTrustedList(type)) {
                return buildList((List<?>) value, depth);
            }
            if (isTrustedMap(type)) {
                return buildMap((Map<?, ?>) value, depth);
            }
            return BuildResult.rejected(RetainedInputEstimator.Rejection.UNSUPPORTED_REPRESENTATION);
        }

        private BuildResult buildArray(Object source, Class<?> componentType, int depth) {
            int length = Array.getLength(source);
            if (length > limits.maxContainerEntries()) {
                return BuildResult.rejected(RetainedInputEstimator.Rejection.CONTAINER_WIDTH_LIMIT);
            }
            if (componentType.isPrimitive()) {
                return BuildResult.accepted(source);
            }
            if (visiting.put(source, Boolean.TRUE) != null) {
                return BuildResult.rejected(RetainedInputEstimator.Rejection.UNSUPPORTED_REPRESENTATION);
            }
            try {
                Object[] target = new Object[length];
                for (int index = 0; index < length; index++) {
                    BuildResult child = build(Array.get(source, index), depth + 1);
                    if (child.rejection != null) return child;
                    target[index] = child.value;
                }
                return BuildResult.accepted(target);
            } finally {
                visiting.remove(source);
            }
        }

        private BuildResult buildList(List<?> source, int depth) {
            int size = source.size();
            if (size > limits.maxContainerEntries()) {
                return BuildResult.rejected(RetainedInputEstimator.Rejection.CONTAINER_WIDTH_LIMIT);
            }
            int length = guardedLength(size, 1, 1);
            if (length < 0) return BuildResult.rejected(RetainedInputEstimator.Rejection.BYTE_LIMIT);
            if (visiting.put(source, Boolean.TRUE) != null) {
                return BuildResult.rejected(RetainedInputEstimator.Rejection.UNSUPPORTED_REPRESENTATION);
            }
            try {
                Object[] target = new Object[length];
                target[0] = LIST;
                for (int index = 0; index < size; index++) {
                    BuildResult child = build(source.get(index), depth + 1);
                    if (child.rejection != null) return child;
                    target[index + 1] = child.value;
                }
                return BuildResult.accepted(target);
            } finally {
                visiting.remove(source);
            }
        }

        private BuildResult buildMap(Map<?, ?> source, int depth) {
            int size = source.size();
            if (size > limits.maxContainerEntries()) {
                return BuildResult.rejected(RetainedInputEstimator.Rejection.CONTAINER_WIDTH_LIMIT);
            }
            int length = guardedLength(size, 2, 1);
            if (length < 0) return BuildResult.rejected(RetainedInputEstimator.Rejection.BYTE_LIMIT);
            if (visiting.put(source, Boolean.TRUE) != null) {
                return BuildResult.rejected(RetainedInputEstimator.Rejection.UNSUPPORTED_REPRESENTATION);
            }
            try {
                Object[] target = new Object[length];
                target[0] = MAP;
                int targetIndex = 1;
                for (Map.Entry<?, ?> entry : source.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        return BuildResult.rejected(
                                RetainedInputEstimator.Rejection.UNSUPPORTED_REPRESENTATION);
                    }
                    BuildResult child = build(entry.getValue(), depth + 1);
                    if (child.rejection != null) return child;
                    target[targetIndex++] = key;
                    target[targetIndex++] = child.value;
                }
                return BuildResult.accepted(target);
            } finally {
                visiting.remove(source);
            }
        }

        private int guardedLength(int size, int multiplier, int addition) {
            long length = (long) size * multiplier + addition;
            long minimumBytes = 24L + length * 8L;
            return length > Integer.MAX_VALUE || minimumBytes > limits.maxRetainedBytes()
                    ? -1 : (int) length;
        }

        private static boolean isTrustedList(Class<?> type) {
            return type == ArrayList.class
                    || type.getName().startsWith("java.util.ImmutableCollections$List");
        }

        private static boolean isTrustedMap(Class<?> type) {
            return type == LinkedHashMap.class
                    || type.getName().startsWith("java.util.ImmutableCollections$Map");
        }
    }

    private record BuildResult(Object value, RetainedInputEstimator.Rejection rejection) {
        private static BuildResult accepted(Object value) {
            return new BuildResult(value, null);
        }

        private static BuildResult rejected(RetainedInputEstimator.Rejection rejection) {
            return new BuildResult(null, rejection);
        }
    }
}
