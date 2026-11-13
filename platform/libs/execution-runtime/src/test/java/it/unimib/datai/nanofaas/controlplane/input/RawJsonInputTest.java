package it.unimib.datai.nanofaas.controlplane.input;

import it.unimib.datai.nanofaas.common.model.RawJson;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** An input kept as its JSON text is priced by its bytes and bounded by the same shape limits. */
class RawJsonInputTest {
    private static final RetainedInputEstimator.Limits LIMITS =
            new RetainedInputEstimator.Limits(4, 10, 20, 1_000);

    private static RawJson raw(int length, int nodes, int depth, int width) {
        return RawJson.of("x".repeat(length).getBytes(StandardCharsets.UTF_8), nodes, depth, width);
    }

    @Test
    void retainedBytesAreTheTextPlusAFixedOverhead() {
        var result = new RetainedInputEstimator(LIMITS).estimate(raw(100, 5, 2, 3));

        assertThat(result).isEqualTo(new RetainedInputEstimator.Measured(164));
    }

    @Test
    void eachShapeLimitStillRejects() {
        var estimator = new RetainedInputEstimator(LIMITS);

        assertThat(estimator.estimate(raw(10, 5, 5, 3)))
                .isEqualTo(new RetainedInputEstimator.Rejected(RetainedInputEstimator.Rejection.DEPTH_LIMIT));
        assertThat(estimator.estimate(raw(10, 5, 2, 11)))
                .isEqualTo(new RetainedInputEstimator.Rejected(RetainedInputEstimator.Rejection.CONTAINER_WIDTH_LIMIT));
        assertThat(estimator.estimate(raw(10, 21, 2, 3)))
                .isEqualTo(new RetainedInputEstimator.Rejected(RetainedInputEstimator.Rejection.NODE_LIMIT));
        assertThat(estimator.estimate(raw(937, 5, 2, 3)))
                .isEqualTo(new RetainedInputEstimator.Rejected(RetainedInputEstimator.Rejection.BYTE_LIMIT));
        assertThat(estimator.estimate(raw(936, 20, 4, 10)))
                .as("exactly at every limit").isEqualTo(new RetainedInputEstimator.Measured(1_000));
    }
}
