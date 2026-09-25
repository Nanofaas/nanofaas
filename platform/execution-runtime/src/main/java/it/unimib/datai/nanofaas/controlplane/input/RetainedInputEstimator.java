package it.unimib.datai.nanofaas.controlplane.input;

import java.lang.reflect.Array;
import java.util.IdentityHashMap;
import java.util.OptionalLong;

/**
 * Conservatively prices a retained invocation representation without reflection or an
 * unbounded object-graph walk. This is an accounting policy, not a whole-heap measurement:
 * allocator padding, framework buffers and transport copies remain outside this estimate.
 *
 * <p>Only arrays containing JSON-like scalar values are accepted as containers. Collection APIs
 * expose logical membership but not backing capacity, so even JDK lists and maps are rejected:
 * their retained bytes cannot be conservatively bounded from {@code size()}. Callers must convert
 * them to an explicitly bounded array representation before admission and retain only that
 * representation.
 *
 * <p>The estimator keeps no state between calls. Shared references and cycles are identified by
 * identity during one estimate and charged as references after their first visit.
 */
public final class RetainedInputEstimator {

    private static final long REFERENCE_BYTES = 8;
    private static final long OBJECT_HEADER_BYTES = 16;
    private static final long ARRAY_HEADER_BYTES = 24;
    private static final long BOXED_SCALAR_BYTES = 24;

    private final Limits limits;

    public RetainedInputEstimator(Limits limits) {
        this.limits = limits;
    }

    public Result estimate(Object input) {
        State state = new State(limits);
        Rejection rejection = state.visit(input, 0);
        return rejection == null ? new Measured(state.bytes) : new Rejected(rejection);
    }

    public record Limits(int maxDepth, int maxContainerEntries, int maxVisitedNodes,
                         long maxRetainedBytes) {
        public Limits {
            if (maxDepth < 0 || maxContainerEntries <= 0 || maxVisitedNodes <= 0
                    || maxRetainedBytes <= 0) {
                throw new IllegalArgumentException("Retained-input limits must be positive");
            }
        }
    }

    public sealed interface Result permits Measured, Rejected {
    }

    public record Measured(long retainedBytes) implements Result {
        public Measured {
            if (retainedBytes < 0) {
                throw new IllegalArgumentException("retainedBytes must not be negative");
            }
        }
    }

    public record Rejected(Rejection reason) implements Result {
        public Rejected {
            if (reason == null) {
                throw new IllegalArgumentException("reason is required");
            }
        }
    }

    public enum Rejection {
        DEPTH_LIMIT,
        CONTAINER_WIDTH_LIMIT,
        NODE_LIMIT,
        BYTE_LIMIT,
        UNSUPPORTED_REPRESENTATION
    }

    static OptionalLong addWithinLimit(long current, long increment, long limit) {
        if (current < 0 || increment < 0 || limit < 0 || current > limit
                || increment > limit - current) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(current + increment);
    }

    private static final class State {
        private final Limits limits;
        private final IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
        private int nodes;
        private long bytes;

        private State(Limits limits) {
            this.limits = limits;
        }

        private Rejection visit(Object value, int depth) {
            if (depth > limits.maxDepth()) {
                return Rejection.DEPTH_LIMIT;
            }
            if (++nodes > limits.maxVisitedNodes()) {
                return Rejection.NODE_LIMIT;
            }
            if (value == null) {
                return add(REFERENCE_BYTES);
            }
            if (value instanceof String string) {
                Rejection rejection = add(OBJECT_HEADER_BYTES + ARRAY_HEADER_BYTES);
                return rejection != null ? rejection : addProduct(string.length(), Character.BYTES);
            }
            if (value instanceof Boolean || value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long || value instanceof Float
                    || value instanceof Double || value instanceof Character) {
                return add(BOXED_SCALAR_BYTES);
            }

            Class<?> type = value.getClass();
            if (type.isArray()) {
                return visitArray(value, type.getComponentType(), depth);
            }
            return Rejection.UNSUPPORTED_REPRESENTATION;
        }

        private Rejection visitArray(Object array, Class<?> componentType, int depth) {
            if (visited.put(array, Boolean.TRUE) != null) {
                return add(REFERENCE_BYTES);
            }
            int length = Array.getLength(array);
            if (length > limits.maxContainerEntries()) {
                return Rejection.CONTAINER_WIDTH_LIMIT;
            }
            Rejection rejection = add(ARRAY_HEADER_BYTES);
            if (rejection != null) {
                return rejection;
            }
            if (componentType.isPrimitive()) {
                return addProduct(length, primitiveBytes(componentType));
            }
            rejection = addProduct(length, REFERENCE_BYTES);
            if (rejection != null) {
                return rejection;
            }
            for (int index = 0; index < length; index++) {
                rejection = visit(Array.get(array, index), depth + 1);
                if (rejection != null) {
                    return rejection;
                }
            }
            return null;
        }

        private Rejection addProduct(long left, long right) {
            OptionalLong product = multiplyWithinLimit(left, right, limits.maxRetainedBytes());
            return product.isPresent() ? add(product.getAsLong()) : Rejection.BYTE_LIMIT;
        }

        private Rejection add(long increment) {
            OptionalLong next = addWithinLimit(bytes, increment, limits.maxRetainedBytes());
            if (next.isEmpty()) {
                return Rejection.BYTE_LIMIT;
            }
            bytes = next.getAsLong();
            return null;
        }

        private static int primitiveBytes(Class<?> type) {
            if (type == boolean.class || type == byte.class) {
                return 1;
            }
            if (type == char.class || type == short.class) {
                return 2;
            }
            if (type == int.class || type == float.class) {
                return 4;
            }
            return 8;
        }

        private static OptionalLong multiplyWithinLimit(long left, long right, long limit) {
            if (left < 0 || right < 0 || limit < 0 || (left != 0 && right > limit / left)) {
                return OptionalLong.empty();
            }
            return OptionalLong.of(left * right);
        }
    }
}
