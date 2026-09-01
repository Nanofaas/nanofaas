package it.unimib.datai.nanofaas.cli.http;

import it.unimib.datai.nanofaas.common.model.ConcurrencyControlConfig;
import it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.OffloadPolicy;
import it.unimib.datai.nanofaas.common.model.ResourceQuantity;
import it.unimib.datai.nanofaas.common.model.ResourceSpec;
import it.unimib.datai.nanofaas.common.model.RuntimeMode;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.common.model.ScalingMetric;
import it.unimib.datai.nanofaas.common.model.ScalingStrategy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FunctionDetailsMatchesTest {

    private static final OffloadPolicy ALWAYS = new OffloadPolicy(null, null, "always");
    private static final OffloadPolicy PRESSURE = new OffloadPolicy(null, null, "pressure");

    private static final ScalingConfig SCALING = new ScalingConfig(
            ScalingStrategy.INTERNAL, 1, 3, List.of(), null);

    private static final ResourceSpec RESOURCES = new ResourceSpec(null, null);

    private static FunctionDetails details(OffloadPolicy offload) {
        return new FunctionDetails("echo", "img", List.of(), Map.of(), null,
                5000, 1, 10, 0, null, ExecutionMode.EXTERNAL, ExecutionMode.EXTERNAL,
                null, null, RuntimeMode.HTTP, null, null, null, offload, null);
    }

    private static FunctionSpec spec(OffloadPolicy offload) {
        return new FunctionSpec("echo", "img", List.of(), Map.of(), null,
                5000, 1, 10, 0, null, ExecutionMode.EXTERNAL, RuntimeMode.HTTP, null, null, null, offload);
    }

    private static FunctionDetails fullDetails() {
        return new FunctionDetails("echo", "img:v1", List.of("run"), Map.of("A", "1"),
                RESOURCES, 5000, 1, 10, 0, "http://ext",
                ExecutionMode.EXTERNAL, ExecutionMode.EXTERNAL, null, null,
                RuntimeMode.HTTP, "serve", SCALING, List.of("secret"), null, null);
    }

    private static FunctionSpec fullSpec() {
        return new FunctionSpec("echo", "img:v1", List.of("run"), Map.of("A", "1"),
                RESOURCES, 5000, 1, 10, 0, "http://ext",
                ExecutionMode.EXTERNAL, RuntimeMode.HTTP, "serve",
                SCALING, List.of("secret"), null);
    }

    /**
     * Builds a spec identical to {@link #fullDetails()} except for the given
     * immutable fields; mutable fields (timeoutMs, concurrency, maxRetries,
     * concurrencyControl) stay equal to the current values so a non-empty patch
     * or an immutable difference is attributable only to the overridden field.
     */
    private static FunctionSpec specWith(String image, List<String> command, Map<String, String> env,
                                         ResourceSpec resources, Integer queueSize, String endpointUrl,
                                         ExecutionMode executionMode, RuntimeMode runtimeMode,
                                         String runtimeCommand, ScalingConfig scalingConfig,
                                         List<String> imagePullSecrets, OffloadPolicy offload) {
        return new FunctionSpec("echo", image, command, env, resources,
                5000, 1, queueSize, 0, endpointUrl, executionMode, runtimeMode,
                runtimeCommand, scalingConfig, imagePullSecrets, offload);
    }

    @Test
    void offloadPolicyChangeAloneTriggersMismatch() {
        // fn apply with a changed offload block must re-register, not silently no-op
        assertThat(details(ALWAYS).matches(spec(PRESSURE))).isFalse();
        assertThat(details(null).matches(spec(ALWAYS))).isFalse();
    }

    @Test
    void identicalOffloadPolicyMatches() {
        assertThat(details(ALWAYS).matches(spec(ALWAYS))).isTrue();
    }

    @Test
    void manifestWithoutOffloadBlockDoesNotForceReRegister() {
        // unspecified in the manifest = keep whatever is registered
        assertThat(details(ALWAYS).matches(spec(null))).isTrue();
    }

    @Test
    void mutablePatchPopulatesChangedMutableFieldsAndLeavesTheRestNull() {
        FunctionDetails current = fullDetails();

        FunctionSpec changed = new FunctionSpec("echo", "img:v1", List.of("run"), Map.of("A", "1"),
                RESOURCES, 7000, 4, 10, 3, "http://ext",
                ExecutionMode.EXTERNAL, RuntimeMode.HTTP, "serve",
                SCALING, List.of("secret"), null);

        FunctionPatch patch = current.mutablePatch(changed);

        assertThat(patch.concurrency()).isEqualTo(4);
        assertThat(patch.timeoutMs()).isEqualTo(7000);
        assertThat(patch.maxRetries()).isEqualTo(3);
        assertThat(patch.concurrencyControl()).isNull();
        assertThat(patch.isEmpty()).isFalse();
    }

    @Test
    void mutablePatchIsEmptyWhenNothingMutableChanges() {
        FunctionDetails current = fullDetails();

        FunctionPatch patch = current.mutablePatch(fullSpec());

        assertThat(patch.isEmpty()).isTrue();
        assertThat(patch.concurrency()).isNull();
        assertThat(patch.timeoutMs()).isNull();
        assertThat(patch.maxRetries()).isNull();
        assertThat(patch.concurrencyControl()).isNull();
    }

    @Test
    void mutablePatchLeavesUnspecifiedMutableValuesNull() {
        FunctionDetails current = fullDetails();

        FunctionSpec unspecified = new FunctionSpec("echo", "img:v1", null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);

        FunctionPatch patch = current.mutablePatch(unspecified);

        assertThat(patch.isEmpty()).isTrue();
    }

    @Test
    void mutablePatchIncludesChangedConcurrencyControl() {
        ScalingConfig currentScaling = new ScalingConfig(ScalingStrategy.INTERNAL, 1, 3, List.of(),
                new ConcurrencyControlConfig(ConcurrencyControlMode.FIXED, 2, 1, 4, null, null, null, null));
        FunctionDetails current = new FunctionDetails("echo", "img:v1", List.of("run"), Map.of("A", "1"),
                RESOURCES, 5000, 1, 10, 0, "http://ext",
                ExecutionMode.EXTERNAL, ExecutionMode.EXTERNAL, null, null,
                RuntimeMode.HTTP, "serve", currentScaling, List.of("secret"), null, null);

        ConcurrencyControlConfig budgeted = new ConcurrencyControlConfig(
                ConcurrencyControlMode.BUDGETED, null, null, null, null, null, null, null, 50L, 1.5);
        FunctionSpec requested = new FunctionSpec("echo", "img:v1", List.of("run"), Map.of("A", "1"),
                RESOURCES, 5000, 1, 10, 0, "http://ext",
                ExecutionMode.EXTERNAL, RuntimeMode.HTTP, "serve",
                new ScalingConfig(ScalingStrategy.INTERNAL, 1, 3, List.of(), budgeted),
                List.of("secret"), null);

        FunctionPatch patch = current.mutablePatch(requested);

        assertThat(patch.concurrencyControl()).isEqualTo(budgeted);
        assertThat(patch.concurrency()).isNull();
        assertThat(patch.isEmpty()).isFalse();
    }

    @Test
    void mutablePatchOmitsAbsentConcurrencyControl() {
        // current has no concurrencyControl; requested scalingConfig absent too
        FunctionDetails current = fullDetails();
        FunctionPatch patch = current.mutablePatch(fullSpec());

        assertThat(patch.concurrencyControl()).isNull();

        // requested scalingConfig present but without concurrencyControl
        FunctionSpec withScalingOnly = specWith("img:v1", List.of("run"), Map.of("A", "1"),
                RESOURCES, 10, "http://ext", ExecutionMode.EXTERNAL, RuntimeMode.HTTP,
                "serve", new ScalingConfig(ScalingStrategy.HPA, 2, 5, List.of(), null),
                List.of("secret"), null);
        assertThat(current.mutablePatch(withScalingOnly).concurrencyControl()).isNull();
    }

    @Test
    void imageCommandEnvResourcesQueueSizeAreImmutable() {
        FunctionDetails current = fullDetails();

        assertThat(current.hasImmutableDifferences(
                specWith("img:v2", List.of("run"), Map.of("A", "1"), RESOURCES, 10, "http://ext",
                        ExecutionMode.EXTERNAL, RuntimeMode.HTTP, "serve", SCALING, List.of("secret"), null)))
                .isTrue();

        assertThat(current.hasImmutableDifferences(
                specWith("img:v1", List.of("other"), Map.of("A", "1"), RESOURCES, 10, "http://ext",
                        ExecutionMode.EXTERNAL, RuntimeMode.HTTP, "serve", SCALING, List.of("secret"), null)))
                .isTrue();

        assertThat(current.hasImmutableDifferences(
                specWith("img:v1", List.of("run"), Map.of("B", "2"), RESOURCES, 10, "http://ext",
                        ExecutionMode.EXTERNAL, RuntimeMode.HTTP, "serve", SCALING, List.of("secret"), null)))
                .isTrue();

        ResourceSpec bigger = new ResourceSpec(new ResourceQuantity(new BigDecimal("0.5"), 128), null);
        assertThat(current.hasImmutableDifferences(
                specWith("img:v1", List.of("run"), Map.of("A", "1"), bigger, 10, "http://ext",
                        ExecutionMode.EXTERNAL, RuntimeMode.HTTP, "serve", SCALING, List.of("secret"), null)))
                .isTrue();

        assertThat(current.hasImmutableDifferences(
                specWith("img:v1", List.of("run"), Map.of("A", "1"), RESOURCES, 99, "http://ext",
                        ExecutionMode.EXTERNAL, RuntimeMode.HTTP, "serve", SCALING, List.of("secret"), null)))
                .isTrue();
    }

    @Test
    void executionModeAndRuntimeModeAndRuntimeCommandAreImmutable() {
        FunctionDetails current = fullDetails();

        assertThat(current.hasImmutableDifferences(
                specWith("img:v1", List.of("run"), Map.of("A", "1"), RESOURCES, 10, "http://ext",
                        ExecutionMode.DEPLOYMENT, RuntimeMode.HTTP, "serve", SCALING, List.of("secret"), null)))
                .isTrue();

        // requested executionMode omitted defaults to DEPLOYMENT, current is EXTERNAL
        assertThat(current.hasImmutableDifferences(
                specWith("img:v1", List.of("run"), Map.of("A", "1"), RESOURCES, 10, "http://ext",
                        null, RuntimeMode.HTTP, "serve", SCALING, List.of("secret"), null)))
                .isTrue();

        assertThat(current.hasImmutableDifferences(
                specWith("img:v1", List.of("run"), Map.of("A", "1"), RESOURCES, 10, "http://ext",
                        ExecutionMode.EXTERNAL, RuntimeMode.STDIO, "serve", SCALING, List.of("secret"), null)))
                .isTrue();

        assertThat(current.hasImmutableDifferences(
                specWith("img:v1", List.of("run"), Map.of("A", "1"), RESOURCES, 10, "http://ext",
                        ExecutionMode.EXTERNAL, RuntimeMode.HTTP, "other", SCALING, List.of("secret"), null)))
                .isTrue();
    }

    @Test
    void scalingStrategyMinMaxAndMetricsAreImmutable() {
        FunctionDetails current = fullDetails();

        assertThat(current.hasImmutableDifferences(
                specWith("img:v1", List.of("run"), Map.of("A", "1"), RESOURCES, 10, "http://ext",
                        ExecutionMode.EXTERNAL, RuntimeMode.HTTP, "serve",
                        new ScalingConfig(ScalingStrategy.HPA, 1, 3, List.of(), null),
                        List.of("secret"), null)))
                .isTrue();

        assertThat(current.hasImmutableDifferences(
                specWith("img:v1", List.of("run"), Map.of("A", "1"), RESOURCES, 10, "http://ext",
                        ExecutionMode.EXTERNAL, RuntimeMode.HTTP, "serve",
                        new ScalingConfig(ScalingStrategy.INTERNAL, 2, 3, List.of(), null),
                        List.of("secret"), null)))
                .isTrue();

        assertThat(current.hasImmutableDifferences(
                specWith("img:v1", List.of("run"), Map.of("A", "1"), RESOURCES, 10, "http://ext",
                        ExecutionMode.EXTERNAL, RuntimeMode.HTTP, "serve",
                        new ScalingConfig(ScalingStrategy.INTERNAL, 1, 5, List.of(), null),
                        List.of("secret"), null)))
                .isTrue();

        assertThat(current.hasImmutableDifferences(
                specWith("img:v1", List.of("run"), Map.of("A", "1"), RESOURCES, 10, "http://ext",
                        ExecutionMode.EXTERNAL, RuntimeMode.HTTP, "serve",
                        new ScalingConfig(ScalingStrategy.INTERNAL, 1, 3,
                                List.of(new ScalingMetric("cpu", "50", "rate(...)")), null),
                        List.of("secret"), null)))
                .isTrue();
    }

    @Test
    void imagePullSecretsAndOffloadAreImmutable() {
        FunctionDetails current = fullDetails();

        assertThat(current.hasImmutableDifferences(
                specWith("img:v1", List.of("run"), Map.of("A", "1"), RESOURCES, 10, "http://ext",
                        ExecutionMode.EXTERNAL, RuntimeMode.HTTP, "serve", SCALING,
                        List.of("other-secret"), null)))
                .isTrue();

        assertThat(current.hasImmutableDifferences(
                specWith("img:v1", List.of("run"), Map.of("A", "1"), RESOURCES, 10, "http://ext",
                        ExecutionMode.EXTERNAL, RuntimeMode.HTTP, "serve", SCALING,
                        List.of("secret"), ALWAYS)))
                .isTrue();
    }

    @Test
    void endpointUrlChangeIsImmutableForExternal() {
        FunctionDetails current = fullDetails();

        assertThat(current.hasImmutableDifferences(
                specWith("img:v1", List.of("run"), Map.of("A", "1"), RESOURCES, 10, "http://other",
                        ExecutionMode.EXTERNAL, RuntimeMode.HTTP, "serve", SCALING, List.of("secret"), null)))
                .isTrue();
    }

    @Test
    void endpointUrlDifferenceIsIgnoredForDeployment() {
        FunctionDetails current = new FunctionDetails("echo", "img:v1", List.of("run"), Map.of("A", "1"),
                RESOURCES, 5000, 1, 10, 0, null,
                ExecutionMode.DEPLOYMENT, ExecutionMode.DEPLOYMENT, null, null,
                RuntimeMode.HTTP, "serve", SCALING, List.of("secret"), null, null);

        FunctionSpec requested = specWith("img:v1", List.of("run"), Map.of("A", "1"), RESOURCES, 10, "http://ext",
                ExecutionMode.DEPLOYMENT, RuntimeMode.HTTP, "serve", SCALING, List.of("secret"), null);

        assertThat(current.hasImmutableDifferences(requested)).isFalse();
    }

    @Test
    void mutableOnlyChangesAreNotImmutable() {
        FunctionDetails current = fullDetails();

        FunctionSpec mutableOnly = new FunctionSpec("echo", "img:v1", List.of("run"), Map.of("A", "1"),
                RESOURCES, 8000, 9, 10, 5, "http://ext",
                ExecutionMode.EXTERNAL, RuntimeMode.HTTP, "serve", SCALING, List.of("secret"), null);

        assertThat(current.hasImmutableDifferences(mutableOnly)).isFalse();
    }

    @Test
    void concurrencyControlChangeIsMutableNotImmutable() {
        ScalingConfig currentScaling = new ScalingConfig(ScalingStrategy.INTERNAL, 1, 3, List.of(),
                new ConcurrencyControlConfig(ConcurrencyControlMode.FIXED, 2, 1, 4, null, null, null, null));
        FunctionDetails current = new FunctionDetails("echo", "img:v1", List.of("run"), Map.of("A", "1"),
                RESOURCES, 5000, 1, 10, 0, "http://ext",
                ExecutionMode.EXTERNAL, ExecutionMode.EXTERNAL, null, null,
                RuntimeMode.HTTP, "serve", currentScaling, List.of("secret"), null, null);

        ConcurrencyControlConfig budgeted = new ConcurrencyControlConfig(
                ConcurrencyControlMode.BUDGETED, null, null, null, null, null, null, null, 50L, 1.5);
        FunctionSpec requested = new FunctionSpec("echo", "img:v1", List.of("run"), Map.of("A", "1"),
                RESOURCES, 5000, 1, 10, 0, "http://ext",
                ExecutionMode.EXTERNAL, RuntimeMode.HTTP, "serve",
                new ScalingConfig(ScalingStrategy.INTERNAL, 1, 3, List.of(), budgeted),
                List.of("secret"), null);

        assertThat(current.hasImmutableDifferences(requested)).isFalse();
    }

    @Test
    void noDifferencesMeansNoImmutableDifferences() {
        assertThat(fullDetails().hasImmutableDifferences(fullSpec())).isFalse();
    }

    @Test
    void mutablePatchConvergesForStatedStaticPerPod() {
        ConcurrencyControlConfig stored = new ConcurrencyControlConfig(
                ConcurrencyControlMode.STATIC_PER_POD, 2, 1, 8, 30_000L, 60_000L, 0.5, 0.15);
        FunctionDetails current = new FunctionDetails("echo", "img", null, null, null,
                5000, 1, 10, 0, null, ExecutionMode.DEPLOYMENT, ExecutionMode.DEPLOYMENT,
                null, null, RuntimeMode.HTTP, null,
                new ScalingConfig(ScalingStrategy.INTERNAL, 1, 3, List.of(), stored),
                null, null, null);

        ConcurrencyControlConfig allNull = new ConcurrencyControlConfig(
                ConcurrencyControlMode.STATIC_PER_POD, null, null, null, null, null, null, null);
        FunctionSpec requested = new FunctionSpec("echo", "img", null, null, null,
                5000, 1, 10, 0, null, ExecutionMode.DEPLOYMENT, RuntimeMode.HTTP, null,
                new ScalingConfig(ScalingStrategy.INTERNAL, 1, 3, List.of(), allNull),
                null, null);

        FunctionPatch patch = current.mutablePatch(requested);

        assertThat(patch.concurrencyControl()).isNull();
        assertThat(patch.isEmpty()).isTrue();
    }

    @Test
    void mutablePatchConvergesForBudgetedManifest() {
        ConcurrencyControlConfig stored = new ConcurrencyControlConfig(
                ConcurrencyControlMode.BUDGETED, null, 1, null, null, null, null, null, 250L, 1.0);
        FunctionDetails current = new FunctionDetails("echo", "img", null, null, null,
                5000, 1, 10, 0, null, ExecutionMode.DEPLOYMENT, ExecutionMode.DEPLOYMENT,
                null, null, RuntimeMode.HTTP, null,
                new ScalingConfig(ScalingStrategy.INTERNAL, 1, 3, List.of(), stored),
                null, null, null);

        ConcurrencyControlConfig modeOnly = new ConcurrencyControlConfig(
                ConcurrencyControlMode.BUDGETED, null, null, null, null, null, null, null, null, null);
        FunctionSpec requested = new FunctionSpec("echo", "img", null, null, null,
                5000, 1, 10, 0, null, ExecutionMode.DEPLOYMENT, RuntimeMode.HTTP, null,
                new ScalingConfig(ScalingStrategy.INTERNAL, 1, 3, List.of(), modeOnly),
                null, null);

        assertThat(current.mutablePatch(requested).concurrencyControl()).isNull();
    }

    @Test
    void mutablePatchStillConvergesForNonDeployment() {
        ConcurrencyControlConfig raw = new ConcurrencyControlConfig(
                ConcurrencyControlMode.BUDGETED, null, null, null, null, null, null, null, null, null);
        FunctionDetails current = new FunctionDetails("echo", "img", null, null, null,
                5000, 1, 10, 0, "http://ext", ExecutionMode.EXTERNAL, ExecutionMode.EXTERNAL,
                null, null, RuntimeMode.HTTP, null,
                new ScalingConfig(ScalingStrategy.INTERNAL, 1, 3, List.of(), raw),
                null, null, null);
        FunctionSpec requested = new FunctionSpec("echo", "img", null, null, null,
                5000, 1, 10, 0, "http://ext", ExecutionMode.EXTERNAL, RuntimeMode.HTTP, null,
                new ScalingConfig(ScalingStrategy.INTERNAL, 1, 3, List.of(), raw),
                null, null);

        assertThat(current.mutablePatch(requested).concurrencyControl()).isNull();
    }

    @Test
    void omittedRuntimeModeIsNotAnImmutableDifference() {
        FunctionDetails current = new FunctionDetails("echo", "img", null, null, null,
                5000, 1, 10, 0, null, ExecutionMode.DEPLOYMENT, ExecutionMode.DEPLOYMENT,
                null, null, null, null, null, null, null, null);
        FunctionSpec requested = new FunctionSpec("echo", "img", null, null, null,
                5000, 1, 10, 0, null, ExecutionMode.DEPLOYMENT, null, null, null, null, null);

        assertThat(current.hasImmutableDifferences(requested)).isFalse();
        assertThat(current.matches(requested)).isTrue();
    }
}
