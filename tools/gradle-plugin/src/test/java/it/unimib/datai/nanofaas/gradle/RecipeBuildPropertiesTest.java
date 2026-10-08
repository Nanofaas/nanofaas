package it.unimib.datai.nanofaas.gradle;

import com.fasterxml.jackson.databind.JsonNode;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecipeBuildPropertiesTest {

    @TempDir
    Path dir;

    private JsonNode data(String yaml) throws IOException {
        Path file = dir.resolve("recipe.yaml");
        Files.writeString(file, "schemaVersion: 2\nname: demo\n" + yaml);
        return new RecipeReader().read(file, null).data();
    }

    @Test
    void nativeOptionsGoToTheirOwnProjectsOnly() throws IOException {
        JsonNode data = data("""
                controlPlane: {modules: [], build: {mode: native, native: {optimization: s}}}
                functions:
                  - {name: word-stats, sdk: java-lite, build: {mode: native, native: {gc: G1, monitoring: [jvmstat]}}}
                  - {name: word-stats, sdk: java, build: {mode: jvm}}
                services:
                  - {name: warm-echo, sdk: java, build: {mode: native, native: {optimization: 2}}}
                """);

        // The explicit control-plane optimization is also build identity, so build-metadata records it.
        assertThat(RecipeBuildProperties.byProject(data)).containsExactlyInAnyOrderEntriesOf(Map.of(
                ":control-plane", Map.of("nativeOptimization", "s"),
                ":functions:java:word-stats-lite", Map.of("nativeGc", "G1", "nativeMonitoring", "jvmstat"),
                ":functions:java:word-stats", Map.of("nanofaasRecipeBuildMode", "jvm"),
                ":services:java:warm-echo", Map.of("nativeOptimization", "2", "nanofaasRecipeBuildMode", "native"),
                ":control-plane-modules:build-metadata", Map.of("nanofaasBuildOptimization", "s")));
    }

    @Test
    void springBootComponentsGetTheirModeWithoutNativeOptions() throws IOException {
        // assembleRecipe names no native task, so a Spring Boot function or service learns from its mode whether to
        // run Spring AOT.
        JsonNode data = data("""
                controlPlane: {modules: [], build: {mode: native}}
                functions: [{name: word-stats, sdk: java, build: {mode: native}}]
                services: [{name: warm-echo, sdk: java, build: {mode: jvm}}]
                """);

        assertThat(RecipeBuildProperties.byProject(data))
                .containsEntry(":functions:java:word-stats", Map.of("nanofaasRecipeBuildMode", "native"))
                .containsEntry(":services:java:warm-echo", Map.of("nanofaasRecipeBuildMode", "jvm"))
                .doesNotContainKey(":control-plane");
    }

    @Test
    void effectiveNativeAppliesDefaultsAndTheG1Rule() throws IOException {
        JsonNode data = data("controlPlane: {modules: [], build: {mode: native, native: {gc: G1, monitoring: [jvmstat]}}}\n");

        assertThat(RecipeBuildProperties.effectiveNative(data.get("controlPlane")))
                .isEqualTo(new RecipeBuildProperties.NativeOptions("3", "G1", List.of("jvmstat", "jfr"), "host", null));
        assertThat(RecipeBuildProperties.effectiveNative(data("controlPlane: {modules: [], build: {mode: jvm}}\n")
                .get("controlPlane"))).isNull();
    }

    @Test
    void containerBuilderPicksTheDistributionFromTheCollector() throws IOException {
        JsonNode data = data("""
                controlPlane: {modules: [], build: {mode: native, builder: container, native: {gc: G1}}}
                services: [{name: warm-echo, sdk: java, build: {mode: native, builder: container}}]
                """);

        assertThat(RecipeBuildProperties.effectiveNative(data.get("controlPlane")))
                .isEqualTo(new RecipeBuildProperties.NativeOptions("3", "G1", List.of("jfr"), "container", "oracle"));
        assertThat(RecipeBuildProperties.effectiveNative(data.at("/services/0")))
                .isEqualTo(new RecipeBuildProperties.NativeOptions("3", "serial", List.of(), "container", "community"));
    }

    @Test
    void identityIsRecordedOnlyWithVariantOrExplicitOptimization() throws IOException {
        assertThat(RecipeBuildProperties.identity(data("controlPlane: {modules: [], build: {mode: native}}\n"))).isNull();
        assertThat(RecipeBuildProperties.identity(data(
                "controlPlane: {modules: [], build: {mode: native, native: {optimization: 3}}}\n")))
                .isEqualTo(new RecipeBuildProperties.Identity(null, "3"));
        assertThat(RecipeBuildProperties.identity(data(
                "controlPlane: {modules: [], build: {mode: native, variant: native-o3}}\n")))
                .isEqualTo(new RecipeBuildProperties.Identity("native-o3", "3"));
        assertThat(RecipeBuildProperties.identity(data("""
                controlPlane: {modules: [], build: {mode: jvm, variant: jvm-c1}, jvm: {args: ['-XX:TieredStopAtLevel=1']}}
                """))).isEqualTo(new RecipeBuildProperties.Identity("jvm-c1", "c1"));
        assertThat(RecipeBuildProperties.byProject(data("controlPlane: {modules: [], build: {mode: jvm, variant: jvm}}\n")))
                .containsEntry(":control-plane-modules:build-metadata",
                        Map.of("nanofaasBuildVariant", "jvm", "nanofaasBuildOptimization", "c2"));
    }

    @Test
    void numericOptimizationIsCanonicalInPropertiesIdentityAndEffectiveOptions() throws IOException {
        for (String value : List.of("3", "3.0", "3e0")) {
            JsonNode data = data("controlPlane: {modules: [build-metadata], build: {mode: native, native: {optimization: "
                    + value + "}}}\n");
            assertThat(RecipeBuildProperties.byProject(data).get(":control-plane"))
                    .containsEntry("nativeOptimization", "3");
            assertThat(RecipeBuildProperties.byProject(data).get(":control-plane-modules:build-metadata"))
                    .containsEntry("nanofaasBuildOptimization", "3");
            assertThat(RecipeBuildProperties.identity(data).optimization()).isEqualTo("3");
            assertThat(RecipeBuildProperties.effectiveNative(data.get("controlPlane")).optimization()).isEqualTo("3");
        }
    }

    @Test
    void jvmTierFollowsTheLastTieredStopAtLevel() {
        assertThat(RecipeBuildProperties.jvmTier(null)).isEqualTo("c2");
        assertThat(RecipeBuildProperties.jvmTier(List.of("-XX:TieredStopAtLevel=1"))).isEqualTo("c1");
        assertThat(RecipeBuildProperties.jvmTier(List.of("-XX:TieredStopAtLevel=1", "-XX:TieredStopAtLevel=4")))
                .isEqualTo("c2");
    }

    @Test
    void recipeOwnedFlagsAreRejected() {
        assertThatThrownBy(() -> RecipeBuildProperties.rejectOwnedFlags(Path.of("recipe.yaml"),
                Set.of("nativeParallelism", "nativeGc")))
                .isInstanceOf(GradleException.class)
                .hasMessageContaining("-PnativeGc cannot be combined with -Precipe")
                .hasMessageContaining("build.native.gc");
        RecipeBuildProperties.rejectOwnedFlags(Path.of("recipe.yaml"),
                Set.of("nativeBuildMemory", "nativeParallelism", "containerdMavenLocal"));
    }

    @Test
    void buildIdentityNeedsTheBuildMetadataModule() throws IOException {
        JsonNode variant = data("controlPlane: {modules: [], build: {mode: jvm, variant: jvm}}\n");
        JsonNode optimization = data("controlPlane: {modules: [], build: {mode: native, native: {optimization: s}}}\n");
        JsonNode functionOnly = data("""
                controlPlane: {modules: [], build: {mode: jvm}}
                functions: [{name: word-stats, sdk: java, build: {mode: native, native: {optimization: s}}}]
                """);

        assertThatThrownBy(() -> RecipeBuildProperties.requireBuildMetadata(Path.of("recipe.yaml"), variant, List.of()))
                .hasMessageContaining("controlPlane.build.variant requires the build-metadata module");
        assertThatThrownBy(() -> RecipeBuildProperties.requireBuildMetadata(Path.of("recipe.yaml"), optimization, List.of()))
                .hasMessageContaining("controlPlane.build.native.optimization requires the build-metadata module");
        RecipeBuildProperties.requireBuildMetadata(Path.of("recipe.yaml"), variant, List.of("build-metadata"));
        RecipeBuildProperties.requireBuildMetadata(Path.of("recipe.yaml"), functionOnly, List.of());
    }
}
