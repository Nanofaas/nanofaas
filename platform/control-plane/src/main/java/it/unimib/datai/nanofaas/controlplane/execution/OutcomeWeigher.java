package it.unimib.datai.nanofaas.controlplane.execution;

import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Weighs and freezes an {@link Outcome} once, at insertion time.
 *
 * <p>The byte budget is what actually bounds the heap, because a <i>readable</i>
 * outcome retains the caller's payload: measured, 20,000 outcomes at 64 KB occupy
 * 1.28 GB (docs/experiments/control-plane-tuning-2026-09/RESULTS.md). The weight is
 * an <b>estimate</b>, not a measurement: it walks the structure once and never
 * re-serializes the payload — re-serializing on every access would cost more than
 * the memory it saves.
 *
 * <p>The walk is bounded in breadth and depth, but a bounded walk must never price
 * the part it skipped at zero: that is what lets 30 distinct 1 MiB payloads fit an
 * 11,600-byte budget (review finding R1). When the retained size cannot be bounded
 * within the limits — the structure is too deep, a container is too wide, a cycle or
 * a shared reference is found, or an opaque object cannot be sized — the outcome is
 * reported <b>not cacheable</b> and the store declines to retain its payload. The
 * deduplication tombstone (the idempotency key) is not affected: it is the key, not
 * the payload, that keeps a replay from re-invoking the function.
 *
 * <p>A conservative estimate may reject some large outcomes; it never silently
 * prices a large subtree at zero. Arithmetic saturates, so an enormous structure
 * reports {@link Long#MAX_VALUE} rather than wrapping negative.
 */
final class OutcomeWeigher {

    /**
     * Outcome, cached FreezeResult wrapper, primitive fields, references and alignment.
     * Payload, key and container backing storage are counted separately. The legacy
     * COMPACT_OUTCOME_BYTES setting calibrates a default budget, not an object size.
     */
    private static final int FIXED_OVERHEAD_BYTES = 128;
    private static final int REFERENCE_BYTES = 16;
    private static final int MAX_DEPTH = 4;
    private static final int MAX_ELEMENTS = 256;
    private static final int MAX_VISITED_VALUES = 1024;
    /** Conservative per-entry cost of a map node: the entry plus its hash bucket. */
    private static final int MAP_ENTRY_BYTES = 64;

    private OutcomeWeigher() {
    }

    /** A weighing verdict: cacheable with a conservative byte weight (key included), or not. */
    record Weighing(boolean cacheable, long weight) {
        static Weighing cacheable(long weight) {
            return new Weighing(true, weight);
        }

        static Weighing notCacheable() {
            return new Weighing(false, 0);
        }
    }

    /** The outcome, with its mutable payload deep-frozen, and its conservative byte weight. */
    record FreezeResult(Outcome outcome, long weight) {
    }

    /**
     * The Caffeine weigher: the outcome's weight as a positive {@code int}, saturated
     * at {@link Integer#MAX_VALUE}. Only ever asked about outcomes the store decided to
     * retain, so the "not cacheable" answer cannot be returned to Caffeine (its weigher
     * has no such signal); the saturated maximum is the defensive fallback.
     */
    static int weightForCache(String executionId, Outcome outcome) {
        Weighing result = weigh(executionId, outcome);
        return result.cacheable() ? weightAsInt(result.weight()) : Integer.MAX_VALUE;
    }

    /** Conservative estimate, without copying: for the weigher and the tests. */
    static Weighing weigh(String executionId, Outcome outcome) {
        Walker walker = new Walker(false);
        walker.walkOutcome(outcome);
        if (!walker.cacheable) {
            return Weighing.notCacheable();
        }
        return Weighing.cacheable(saturatingAdd(walker.weight, stringBytes(executionId)));
    }

    /**
     * Deep-freezes the outcome's mutable payload into an unmodifiable, unshared copy so
     * that nothing can mutate it (and grow its retained size) after it was weighed.
     * Returns {@code null} when the payload cannot be bounded within the traversal
     * limits, in which case the caller must not retain it.
     */
    @Nullable
    static FreezeResult freeze(String executionId, Outcome outcome) {
        return freeze(executionId, outcome, Long.MAX_VALUE);
    }

    @Nullable
    static FreezeResult freeze(String executionId, Outcome outcome, long maximumWeight) {
        Walker walker = new Walker(true, maximumWeight);
        walker.add(stringBytes(executionId));
        Outcome frozen = walker.freezeOutcome(outcome);
        if (frozen == null) {
            return null;
        }
        return new FreezeResult(frozen, walker.weight);
    }

    /** Saturating long addition: the estimator never wraps to a negative weight. */
    static long saturatingAdd(long a, long b) {
        if (a > Long.MAX_VALUE - b) {
            return Long.MAX_VALUE;
        }
        return a + b;
    }

    /** Saturating long multiplication for non-negative operands. */
    static long saturatingMultiply(long a, long b) {
        if (a == 0 || b == 0) {
            return 0;
        }
        if (a > Long.MAX_VALUE / b) {
            return Long.MAX_VALUE;
        }
        return a * b;
    }

    /** A long weight turned into Caffeine's non-negative {@code int}. */
    static int weightAsInt(long weight) {
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, weight));
    }

    /**
     * A string's retained character bytes: one per char when it is compact Latin-1,
     * two when it is UTF-16. The distinction matters — a UTF-16 string of the same
     * length retains twice the bytes, and pricing both at {@code length} is exactly
     * the kind of silent underestimate the byte budget exists to prevent.
     */
    private static long stringBytes(String s) {
        // Large strings are sized in O(1); short compact strings keep a tighter estimate.
        long characters = s.length() <= 64 && isLatin1(s) ? s.length() : 2L * s.length();
        return 48 + characters; // String, backing array and alignment allowance
    }

    private static boolean isLatin1(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0xFF) {
                return false;
            }
        }
        return true;
    }

    /**
     * One bounded walk over the outcome. It accumulates the weight and, when
     * {@code copy} is set, builds an unmodifiable deep copy of the mutable containers
     * so the retained value can no longer be mutated after weighing.
     */
    private static final class Walker {
        /** Containers already walked; a repeat is a cycle or a shared reference. */
        private final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        private final boolean copy;
        private final long maximumWeight;
        private long weight;
        private int visited;
        private boolean cacheable = true;

        Walker(boolean copy) { this(copy, Long.MAX_VALUE); }

        Walker(boolean copy, long maximumWeight) {
            this.copy = copy;
            this.maximumWeight = maximumWeight;
        }

        void add(long bytes) {
            weight = saturatingAdd(weight, bytes);
            if (weight > maximumWeight) cacheable = false;
        }

        void walkOutcome(Outcome outcome) {
            walk(outcome.output(), 0);
            walk(outcome.headers(), 0);
            walk(outcome.encoding(), 0);
            if (outcome.error() != null) {
                walk(outcome.error().code(), 0);
                walk(outcome.error().message(), 0);
            }
            add(FIXED_OVERHEAD_BYTES);
        }

        @Nullable
        Outcome freezeOutcome(Outcome outcome) {
            Object output = walk(outcome.output(), 0);
            Object headers = walk(outcome.headers(), 0);
            walk(outcome.encoding(), 0);
            if (outcome.error() != null) {
                walk(outcome.error().code(), 0);
                walk(outcome.error().message(), 0);
            }
            add(FIXED_OVERHEAD_BYTES);
            if (!cacheable) {
                return null;
            }
            return new Outcome(
                    outcome.state(),
                    outcome.startedAtMs(),
                    outcome.finishedAtMs(),
                    output,
                    outcome.error(),
                    castHeaders(headers),
                    outcome.encoding(),
                    outcome.statusCode(),
                    outcome.initDurationMs(),
                    outcome.coldStart(),
                    outcome.readable()
            );
        }

        @SuppressWarnings("unchecked")
        private static Map<String, String> castHeaders(Object headers) {
            return (Map<String, String>) headers;
        }

        @Nullable
        Object walk(Object value, int depth) {
            if (++visited > MAX_VISITED_VALUES) {
                cacheable = false;
            }
            if (value == null || !cacheable) {
                return null;
            }
            if (depth > MAX_DEPTH) {
                cacheable = false;
                return null;
            }
            return switch (value) {
                case String s -> {
                    add(stringBytes(s));
                    yield s;
                }
                case Byte _, Short _, Integer _, Long _, Float _, Double _, Boolean _, Character _ -> {
                    add(32);
                    yield value;
                }
                case Map<?, ?> map -> walkMap(map, depth);
                case List<?> list -> walkList(list, depth);
                case Set<?> set -> walkSet(set, depth);
                case byte[] array -> primitiveArray(array, 1);
                case boolean[] array -> primitiveArray(array, 1);
                case char[] array -> primitiveArray(array, 2);
                case short[] array -> primitiveArray(array, 2);
                case int[] array -> primitiveArray(array, 4);
                case float[] array -> primitiveArray(array, 4);
                case long[] array -> primitiveArray(array, 8);
                case double[] array -> primitiveArray(array, 8);
                case Object[] array -> walkObjectArray(array, depth);
                default -> {
                    cacheable = false;
                    yield null;
                }
            };
        }

        /** A primitive array's size is known without walking: header plus element bytes. */
        private Object primitiveArray(Object array, long elementBytes) {
            int length = java.lang.reflect.Array.getLength(array);
            add(REFERENCE_BYTES + saturatingMultiply(length, elementBytes));
            if (!cacheable) return null;
            return copy ? cloneArray(array) : array;
        }

        @Nullable
        private Object walkObjectArray(Object[] array, int depth) {
            if (!enter(array)) {
                return null;
            }
            add(REFERENCE_BYTES);
            int length = array.length;
            if (length > MAX_ELEMENTS) {
                cacheable = false;
                return null;
            }
            add(saturatingMultiply(length, REFERENCE_BYTES));
            Object[] frozen = copy ? new Object[length] : null;
            for (int i = 0; i < length && cacheable; i++) {
                Object element = walk(array[i], depth + 1);
                if (frozen != null) {
                    frozen[i] = element;
                }
            }
            return frozen;
        }

        @Nullable
        private Object walkMap(Map<?, ?> map, int depth) {
            if (!enter(map)) {
                return null;
            }
            add(96);
            if (map.size() > MAX_ELEMENTS) {
                cacheable = false;
                return null;
            }
            Map<Object, Object> frozen = copy ? new LinkedHashMap<>(map.size()) : null;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!cacheable) {
                    break;
                }
                Object key = walk(entry.getKey(), depth + 1);
                Object value = walk(entry.getValue(), depth + 1);
                add(MAP_ENTRY_BYTES);
                if (frozen != null) {
                    frozen.put(key, value);
                }
            }
            return frozen != null ? Collections.unmodifiableMap(frozen) : map;
        }

        @Nullable
        private Object walkList(List<?> list, int depth) {
            if (!enter(list)) {
                return null;
            }
            add(REFERENCE_BYTES);
            if (list.size() > MAX_ELEMENTS) {
                cacheable = false;
                return null;
            }
            add(64 + saturatingMultiply(list.size(), 8));
            List<Object> frozen = copy ? new ArrayList<>(list.size()) : null;
            for (Object element : list) {
                if (!cacheable) {
                    break;
                }
                Object frozenElement = walk(element, depth + 1);
                if (frozen != null) {
                    frozen.add(frozenElement);
                }
            }
            return frozen != null ? Collections.unmodifiableList(frozen) : list;
        }

        @Nullable
        private Object walkSet(Set<?> set, int depth) {
            if (!enter(set)) {
                return null;
            }
            add(REFERENCE_BYTES);
            if (set.size() > MAX_ELEMENTS) {
                cacheable = false;
                return null;
            }
            add(96 + saturatingMultiply(set.size(), MAP_ENTRY_BYTES));
            Set<Object> frozen = copy ? new LinkedHashSet<>(set.size()) : null;
            for (Object element : set) {
                if (!cacheable) {
                    break;
                }
                Object frozenElement = walk(element, depth + 1);
                if (frozen != null) {
                    frozen.add(frozenElement);
                }
            }
            return frozen != null ? Collections.unmodifiableSet(frozen) : set;
        }

        /** Records a container and fails the walk when the same one is seen twice. */
        private boolean enter(Object container) {
            if (seen.put(container, Boolean.TRUE) != null) {
                cacheable = false;
                return false;
            }
            return true;
        }
    }

    /** Clones a primitive array by type, avoiding a second reflection call per element. */
    private static Object cloneArray(Object array) {
        return switch (array) {
            case boolean[] a -> a.clone();
            case byte[] a -> a.clone();
            case char[] a -> a.clone();
            case short[] a -> a.clone();
            case int[] a -> a.clone();
            case float[] a -> a.clone();
            case long[] a -> a.clone();
            case double[] a -> a.clone();
            default -> array;
        };
    }
}
