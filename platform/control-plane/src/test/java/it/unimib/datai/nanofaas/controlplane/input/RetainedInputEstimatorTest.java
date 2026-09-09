package it.unimib.datai.nanofaas.controlplane.input;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RetainedInputEstimatorTest {

    @Test
    void flatJsonRepresentationGetsAConservativePositiveMeasurement() {
        RetainedInputEstimator estimator = estimator(4, 8, 32, 10_000);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("enabled", true);
        input.put("name", "café");
        input.put("values", Arrays.asList(1, 2L, null));

        RetainedInputEstimator.Result result = estimator.estimate(input);

        assertThat(result).isInstanceOf(RetainedInputEstimator.Measured.class);
        assertThat(((RetainedInputEstimator.Measured) result).retainedBytes()).isGreaterThan(13L);
    }

    @Test
    void shapeBeyondTheDepthBoundIsRejectedInsteadOfAssignedAFrontierToken() {
        RetainedInputEstimator estimator = estimator(3, 8, 32, 10_000);
        Object input = "leaf";
        for (int depth = 0; depth < 4; depth++) {
            input = List.of(input);
        }

        assertThat(estimator.estimate(input))
                .isEqualTo(new RetainedInputEstimator.Rejected(
                        RetainedInputEstimator.Rejection.DEPTH_LIMIT));
    }

    @Test
    void wideJdkContainerIsRejectedAtTheConfiguredWidth() {
        List<Object> input = new ArrayList<>(Collections.nCopies(9, "value"));
        RetainedInputEstimator estimator = estimator(4, 8, 32, 10_000);

        assertThat(estimator.estimate(input))
                .isEqualTo(new RetainedInputEstimator.Rejected(
                        RetainedInputEstimator.Rejection.CONTAINER_WIDTH_LIMIT));
    }

    @Test
    void largeSupportedValueCrossingTheByteBudgetIsRejectedWithoutArithmeticWraparound() {
        RetainedInputEstimator estimator = estimator(4, 8, 32, 64);

        assertThat(estimator.estimate("x".repeat(128)))
                .isEqualTo(new RetainedInputEstimator.Rejected(
                        RetainedInputEstimator.Rejection.BYTE_LIMIT));
        assertThat(RetainedInputEstimator.addWithinLimit(Long.MAX_VALUE - 2, 3, Long.MAX_VALUE))
                .isEmpty();
    }

    @Test
    void nodeLimitBoundsTraversalEvenWhenDepthAndWidthStillAllowTheShape() {
        RetainedInputEstimator estimator = estimator(8, 8, 4, 10_000);

        assertThat(estimator.estimate(List.of(1, 2, 3, 4)))
                .isEqualTo(new RetainedInputEstimator.Rejected(
                        RetainedInputEstimator.Rejection.NODE_LIMIT));
    }

    @Test
    void opaqueLocalObjectIsRejectedRatherThanGivenASymbolicEstimate() {
        RetainedInputEstimator estimator = estimator(4, 8, 32, 10_000);

        assertThat(estimator.estimate(new OpaqueLocalInput()))
                .isEqualTo(new RetainedInputEstimator.Rejected(
                        RetainedInputEstimator.Rejection.UNSUPPORTED_REPRESENTATION));
    }

    @Test
    void customCollectionIsRejectedWithoutInvokingPotentiallyUnboundedUserCode() {
        AtomicInteger calls = new AtomicInteger();
        List<Object> custom = new ArrayList<>() {
            @Override
            public int size() {
                calls.incrementAndGet();
                throw new AssertionError("custom LOCAL collection code must not run");
            }
        };
        RetainedInputEstimator estimator = estimator(4, 8, 32, 10_000);

        assertThat(estimator.estimate(custom))
                .isEqualTo(new RetainedInputEstimator.Rejected(
                        RetainedInputEstimator.Rejection.UNSUPPORTED_REPRESENTATION));
        assertThat(calls).hasValue(0);
    }

    @Test
    void repeatedOpaqueRejectionsDoNotAccumulateEstimatorState() {
        RetainedInputEstimator estimator = estimator(4, 8, 32, 10_000);
        for (int attempt = 0; attempt < 1_000; attempt++) {
            assertThat(estimator.estimate(new OpaqueLocalInput()))
                    .isInstanceOf(RetainedInputEstimator.Rejected.class);
        }

        assertThat(estimator.estimate(Map.of("accepted", "after rejections")))
                .isInstanceOf(RetainedInputEstimator.Measured.class);
    }

    private static RetainedInputEstimator estimator(int depth, int width, int nodes, long bytes) {
        return new RetainedInputEstimator(new RetainedInputEstimator.Limits(depth, width, nodes, bytes));
    }

    private static final class OpaqueLocalInput {
        private final byte[] retained = new byte[1_024];
    }
}
