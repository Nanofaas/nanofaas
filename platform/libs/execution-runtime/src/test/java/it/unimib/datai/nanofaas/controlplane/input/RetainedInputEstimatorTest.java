package it.unimib.datai.nanofaas.controlplane.input;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RetainedInputEstimatorTest {

    @Test
    void scalarsArePricedByTheirJavaRepresentation() {
        RetainedInputEstimator estimator = estimator(4, 8, 32, 10_000);

        assertThat(estimator.estimate("café")).isEqualTo(new RetainedInputEstimator.Measured(48));
        assertThat(estimator.estimate(42L)).isEqualTo(new RetainedInputEstimator.Measured(24));
        assertThat(estimator.estimate(true)).isEqualTo(new RetainedInputEstimator.Measured(24));
        assertThat(estimator.estimate(null)).isEqualTo(new RetainedInputEstimator.Measured(8));
    }

    @Test
    void anArrayIsNotARepresentationEither() {
        RetainedInputEstimator estimator = estimator(4, 8, 32, 10_000);

        assertThat(estimator.estimate(new Object[]{"a", 1}))
                .isEqualTo(new RetainedInputEstimator.Rejected(
                        RetainedInputEstimator.Rejection.UNSUPPORTED_REPRESENTATION));
    }

    @Test
    void largeSupportedValueCrossingTheByteBudgetIsRejectedWithoutArithmeticWraparound() {
        RetainedInputEstimator estimator = estimator(4, 8, 32, 64);

        assertThat(estimator.estimate("x".repeat(128)))
                .isEqualTo(new RetainedInputEstimator.Rejected(
                        RetainedInputEstimator.Rejection.BYTE_LIMIT));
    }

    @Test
    void sparseOverCapacityJdkListIsRejectedInsteadOfPricedByLogicalSize() {
        ArrayList<Object> sparse = new ArrayList<>(1_000_000);
        sparse.add("one logical element");
        RetainedInputEstimator estimator = estimator(4, 8, 32, 10_000);

        assertThat(estimator.estimate(sparse))
                .isEqualTo(new RetainedInputEstimator.Rejected(
                        RetainedInputEstimator.Rejection.UNSUPPORTED_REPRESENTATION));
    }

    @Test
    void sparseOverCapacityJdkMapIsRejectedInsteadOfPricingItsEmptyLogicalSize() {
        HashMap<String, Object> sparse = new HashMap<>(1_000_000);
        sparse.put("allocate-table", true);
        sparse.remove("allocate-table");
        RetainedInputEstimator estimator = estimator(4, 8, 32, 10_000);

        assertThat(estimator.estimate(sparse))
                .isEqualTo(new RetainedInputEstimator.Rejected(
                        RetainedInputEstimator.Rejection.UNSUPPORTED_REPRESENTATION));
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

        assertThat(estimator.estimate("accepted after rejections"))
                .isInstanceOf(RetainedInputEstimator.Measured.class);
    }

    private static RetainedInputEstimator estimator(int depth, int width, int nodes, long bytes) {
        return new RetainedInputEstimator(new RetainedInputEstimator.Limits(depth, width, nodes, bytes));
    }

    private static final class OpaqueLocalInput {
        @SuppressWarnings("UnusedVariable") // Makes the rejected opaque object retain a measurable payload.
        private final byte[] retained = new byte[1_024];
    }
}
