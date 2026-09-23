package it.unimib.datai.nanofaas.controlplane.input;

import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Array;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalInvocationInputTest {

    private static RetainedInputEstimator.Limits limits(int depth, int width, int nodes, long bytes) {
        return new RetainedInputEstimator.Limits(depth, width, nodes, bytes);
    }

    @Test
    void canonicalizesJsonCollectionsToMeasuredArraysWithoutRetainingSources() {
        ArrayList<Object> nested = new ArrayList<>(List.of("x", 7));
        LinkedHashMap<String, Object> source = new LinkedHashMap<>();
        source.put("nested", nested);
        source.put("flag", true);
        InvocationRequest request = new InvocationRequest(source, Map.of("m", "v"), Map.of("h", "v"));

        CanonicalInvocationInput.Result result = CanonicalInvocationInput.canonicalize(
                request, limits(12, 32, 128, 32_768));

        assertThat(result).isInstanceOf(CanonicalInvocationInput.Accepted.class);
        CanonicalInvocationInput.Accepted accepted = (CanonicalInvocationInput.Accepted) result;
        assertThat(accepted.canonicalRequest().input()).isInstanceOf(Object[].class);
        assertThat(accepted.retainedBytes()).isPositive();
        assertThat(containsIdentity(accepted.canonicalRequest().input(), source)).isFalse();
        assertThat(containsIdentity(accepted.canonicalRequest().input(), nested)).isFalse();
        assertThat(accepted.materializeRequest()).isEqualTo(request);
    }

    @Test
    void rejectsDeepWideAndLargeCollectionsBeforePublication() {
        Object deep = List.of(List.of(List.of("leaf")));
        Object wide = new ArrayList<>(List.of(1, 2, 3));
        Object large = List.of("012345678901234567890123456789");

        assertThat(rejection(deep, limits(1, 16, 64, 8_192)))
                .isEqualTo(RetainedInputEstimator.Rejection.DEPTH_LIMIT);
        assertThat(rejection(wide, limits(8, 2, 64, 8_192)))
                .isEqualTo(RetainedInputEstimator.Rejection.CONTAINER_WIDTH_LIMIT);
        assertThat(rejection(large, limits(8, 16, 64, 48)))
                .isEqualTo(RetainedInputEstimator.Rejection.BYTE_LIMIT);
    }

    @Test
    void rejectsCustomCollectionsWithoutInvokingTheirTraversalMethods() {
        AtomicBoolean touched = new AtomicBoolean();
        List<Object> custom = new AbstractList<>() {
            @Override public Object get(int index) {
                touched.set(true);
                throw new AssertionError("custom collection was traversed");
            }

            @Override public int size() {
                touched.set(true);
                throw new AssertionError("custom collection size was invoked");
            }
        };

        assertThat(rejection(custom, limits(8, 16, 64, 8_192)))
                .isEqualTo(RetainedInputEstimator.Rejection.UNSUPPORTED_REPRESENTATION);
        assertThat(touched).isFalse();
    }

    @Test
    void rejectsCustomNumberObjectsEvenWhenNestedInAnOtherwiseAcceptedArray() {
        Number opaque = new Number() {
            @SuppressWarnings("UnusedVariable") // Verifies that opaque Number implementations may hide an object graph.
            private final Object hiddenGraph = new Object();

            @Override public int intValue() { return 0; }
            @Override public long longValue() { return 0; }
            @Override public float floatValue() { return 0; }
            @Override public double doubleValue() { return 0; }
        };

        assertThat(rejection(new Object[]{opaque}, limits(8, 16, 64, 8_192)))
                .isEqualTo(RetainedInputEstimator.Rejection.UNSUPPORTED_REPRESENTATION);
    }

    @Test
    void keepsAnAlreadyBoundedAcceptedArrayAsTheRetainedRepresentation() {
        Object[] acceptedInput = {"x", new int[]{1, 2, 3}};
        InvocationRequest request = new InvocationRequest(acceptedInput, Map.of());

        CanonicalInvocationInput.Accepted accepted = (CanonicalInvocationInput.Accepted)
                CanonicalInvocationInput.canonicalize(request, limits(8, 16, 64, 8_192));

        assertThat(accepted.canonicalRequest().input()).isSameAs(acceptedInput);
        assertThat(accepted.materializeRequest()).isSameAs(accepted.canonicalRequest());
    }

    @Test
    void repeatedRejectionDoesNotPoisonAFollowingAcceptedInput() {
        RetainedInputEstimator.Limits limits = limits(4, 4, 16, 512);
        for (int i = 0; i < 1_000; i++) {
            assertThat(CanonicalInvocationInput.canonicalize(
                    new InvocationRequest(new Object(), Map.of()), limits))
                    .isInstanceOf(CanonicalInvocationInput.Rejected.class);
        }

        assertThat(CanonicalInvocationInput.canonicalize(
                new InvocationRequest(List.of("ok"), Map.of()), limits))
                .isInstanceOf(CanonicalInvocationInput.Accepted.class);
    }

    private static RetainedInputEstimator.Rejection rejection(
            Object input, RetainedInputEstimator.Limits limits) {
        CanonicalInvocationInput.Result result = CanonicalInvocationInput.canonicalize(
                new InvocationRequest(input, Map.of()), limits);
        return ((CanonicalInvocationInput.Rejected) result).reason();
    }

    @SuppressWarnings("ReferenceEquality") // This helper verifies graph identity, not value equality.
    private static boolean containsIdentity(Object value, Object expected) {
        if (value == expected) {
            return true;
        }
        if (value == null || !value.getClass().isArray()) {
            return false;
        }
        for (int index = 0; index < Array.getLength(value); index++) {
            if (containsIdentity(Array.get(value, index), expected)) {
                return true;
            }
        }
        return false;
    }
}
