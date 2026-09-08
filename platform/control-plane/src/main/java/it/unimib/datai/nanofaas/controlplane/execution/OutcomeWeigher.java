package it.unimib.datai.nanofaas.controlplane.execution;

import it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreProperties;

import java.util.Collection;
import java.util.Map;

/**
 * Estimates how much heap an outcome retains, once, at insertion time.
 *
 * <p>The count cap on outcomes assumes they all weigh the same. For a compact outcome
 * that holds (116 bytes); for a <i>readable</i> one, which retains the caller's payload,
 * it does not: 20,000 outcomes at 64 KB measure 1.28 GB
 * (docs/experiments/control-plane-tuning-2026-09/RESULTS.md).
 *
 * <p>The weight is an <b>estimate</b>, not a measurement: it walks the structure once and
 * never re-serializes the payload — re-serializing on every access would cost more than
 * the memory it saves. The walk is bounded in breadth and depth: a pathologically nested
 * payload must cost like the others, not more than them.
 *
 * <p>An outcome whose estimated weight alone exceeds the whole budget cannot be retained:
 * Caffeine admits it and evicts it immediately. A5's guarantee still holds — the key
 * remains as a tombstone and the replay answers 410 rather than re-invoking the function —
 * but the payload is unrecoverable. With the default budget (11.6 MB) that needs a single
 * 11 MB outcome; anyone retaining payloads that large must raise
 * {@code max-outcome-bytes}.
 */
final class OutcomeWeigher {

    /**
     * Structural overhead of the Outcome object: object header, primitive fields and
     * references, without the payload or the HTTP headers. Chosen so that a compact
     * outcome — a small output and nothing else — weighs about the
     * {@link ExecutionStoreProperties#COMPACT_OUTCOME_BYTES} bytes the count cap was
     * calibrated on: at the default budget, as many fit as fitted before.
     */
    private static final int FIXED_OVERHEAD_BYTES = 96;
    private static final int REFERENCE_BYTES = 16;
    private static final int MAX_DEPTH = 4;
    private static final int MAX_ELEMENTS = 256;
    /** Cost attributed to an object we cannot walk. */
    private static final int OPAQUE_BYTES = 64;

    private OutcomeWeigher() {
    }

    static int weigh(Outcome outcome) {
        long total = FIXED_OVERHEAD_BYTES;
        total += estimate(outcome.output(), 0);
        total += estimate(outcome.headers(), 0);
        total += outcome.encoding() == null ? 0 : outcome.encoding().length();
        if (outcome.error() != null) {
            total += estimate(outcome.error().code(), 0) + estimate(outcome.error().message(), 0);
        }
        // Caffeine's weight is an int, and an outcome cannot weigh zero or weight-based
        // eviction would have no way to evict it.
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, total));
    }

    private static long estimate(Object value, int depth) {
        if (value == null || depth > MAX_DEPTH) {
            return 0;
        }
        return switch (value) {
            // Compact strings use one byte per Latin-1 character: the length is the
            // right estimate, not twice it.
            // Without adding the reference: a compact outcome must stay compact, and the
            // object overhead is already counted once in FIXED_OVERHEAD_BYTES.
            case String s -> s.length();
            case byte[] bytes -> REFERENCE_BYTES + bytes.length;
            case Number _, Boolean _, Character _ -> REFERENCE_BYTES;
            case Map<?, ?> map -> estimateMap(map, depth);
            case Collection<?> collection -> estimateCollection(collection, depth);
            default -> OPAQUE_BYTES;
        };
    }

    private static long estimateMap(Map<?, ?> map, int depth) {
        long total = REFERENCE_BYTES;
        int seen = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (seen++ >= MAX_ELEMENTS) {
                break;
            }
            total += estimate(entry.getKey(), depth + 1) + estimate(entry.getValue(), depth + 1);
        }
        return total;
    }

    private static long estimateCollection(Collection<?> collection, int depth) {
        long total = REFERENCE_BYTES;
        int seen = 0;
        for (Object element : collection) {
            if (seen++ >= MAX_ELEMENTS) {
                break;
            }
            total += estimate(element, depth + 1);
        }
        return total;
    }
}
