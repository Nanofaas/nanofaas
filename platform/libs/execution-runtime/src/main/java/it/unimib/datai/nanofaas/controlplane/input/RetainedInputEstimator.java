package it.unimib.datai.nanofaas.controlplane.input;

import it.unimib.datai.nanofaas.common.model.RawJson;

/**
 * Prices what an invocation retains for its input, and bounds its shape.
 *
 * <p>A structured payload reaches the control plane as {@link RawJson}: JSON kept as text, whose
 * retained size is its bytes and whose shape was counted once, when it was read. A scalar is
 * priced by its Java representation. Nothing else is accepted: a collection or any other object
 * has no size that can be bounded from the outside, and no caller that produces one.
 *
 * <p>This is an accounting policy, not a whole-heap measurement: allocator padding, framework
 * buffers and transport copies remain outside it.
 */
public final class RetainedInputEstimator {

    private static final long REFERENCE_BYTES = 8;
    private static final long OBJECT_HEADER_BYTES = 16;
    private static final long ARRAY_HEADER_BYTES = 24;
    private static final long BOXED_SCALAR_BYTES = 24;
    /** The holder object (header, three counts, array reference) plus its byte array's header. */
    private static final long RAW_JSON_OVERHEAD_BYTES = 64;

    private final Limits limits;

    public RetainedInputEstimator(Limits limits) {
        this.limits = limits;
    }

    public Result estimate(Object input) {
        long bytes;
        if (input == null) {
            bytes = REFERENCE_BYTES;
        } else if (input instanceof String string) {
            bytes = OBJECT_HEADER_BYTES + ARRAY_HEADER_BYTES + (long) string.length() * Character.BYTES;
        } else if (input instanceof Boolean || input instanceof Byte || input instanceof Short
                || input instanceof Integer || input instanceof Long || input instanceof Float
                || input instanceof Double || input instanceof Character) {
            bytes = BOXED_SCALAR_BYTES;
        } else if (input instanceof RawJson raw) {
            if (raw.depth() > limits.maxDepth()) {
                return new Rejected(Rejection.DEPTH_LIMIT);
            }
            if (raw.width() > limits.maxContainerEntries()) {
                return new Rejected(Rejection.CONTAINER_WIDTH_LIMIT);
            }
            if (raw.nodes() > limits.maxVisitedNodes()) {
                return new Rejected(Rejection.NODE_LIMIT);
            }
            bytes = RAW_JSON_OVERHEAD_BYTES + raw.length();
        } else {
            return new Rejected(Rejection.UNSUPPORTED_REPRESENTATION);
        }
        return bytes > limits.maxRetainedBytes() ? new Rejected(Rejection.BYTE_LIMIT) : new Measured(bytes);
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
}
