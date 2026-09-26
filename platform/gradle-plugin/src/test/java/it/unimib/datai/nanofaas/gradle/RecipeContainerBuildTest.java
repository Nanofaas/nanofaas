package it.unimib.datai.nanofaas.gradle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecipeContainerBuildTest {

    private static final Path RECIPE = Path.of("recipe.yaml");
    private static final String REVISION = "a".repeat(40);

    @TempDir
    Path dir;

    private JsonNode data(String yaml) throws IOException {
        Path file = dir.resolve("recipe.yaml");
        Files.writeString(file, "schemaVersion: 2\nname: demo\n" + yaml);
        return new RecipeReader().read(file, null).data();
    }

    private static JsonNode source(boolean dirty) throws IOException {
        return new ObjectMapper().readTree("{\"revision\": \"" + REVISION + "\", \"dirty\": " + dirty + "}");
    }

    @Test
    void controlPlaneCarriesModulesIdentityAndRevision() throws IOException {
        JsonNode data = data("""
                controlPlane: {modules: [build-metadata], build: {mode: native, builder: container, variant: native-os,
                               native: {optimization: s, gc: G1}}}
                """);

        assertThat(RecipeContainerBuild.gradleArgs(RECIPE, data, ":control-plane", List.of("build-metadata"),
                source(true), Map.of("nativeBuildMemory", "6g"))).containsExactly(
                "-PnanofaasBuildType=native", "-PnativeOptimization=s", "-PnativeGc=G1",
                "-PcontrolPlaneModules=build-metadata",
                "-PnanofaasBuildOptimization=s", "-PnanofaasBuildVariant=native-os",
                "-PnanofaasBuildRevision=" + REVISION, "-PnanofaasBuildDirty=true",
                "-PnativeBuildMemory=6g");
    }

    @Test
    void emptyModulesSelectNoneAndContainerdGetsItsRepository() throws IOException {
        JsonNode data = data("controlPlane: {modules: [], build: {mode: native, builder: container}}\n");
        assertThat(RecipeContainerBuild.gradleArgs(RECIPE, data, ":control-plane", List.of(), NullNode.getInstance(),
                Map.of())).containsExactly("-PnanofaasBuildType=native", "-PcontrolPlaneModules=none");

        JsonNode containerd = data("""
                controlPlane: {modules: [containerd-deployment-provider], build: {mode: native, builder: container}}
                """);
        assertThat(RecipeContainerBuild.gradleArgs(RECIPE, containerd, ":control-plane",
                List.of("containerd-deployment-provider"), NullNode.getInstance(), Map.of()))
                .containsSubsequence("-PcontrolPlaneModules=containerd-deployment-provider",
                        "-PcontainerdMavenLocal=true", "-Dmaven.repo.local=/tmp/containerd-m2");
    }

    @Test
    void functionsAndServicesGetOnlyTheirOwnNativeFlags() throws IOException {
        JsonNode data = data("""
                controlPlane: {modules: [build-metadata], build: {mode: jvm, variant: jvm}}
                services: [{name: warm-echo, sdk: java, build: {mode: native, builder: container, native: {optimization: 2}}}]
                """);

        assertThat(RecipeContainerBuild.gradleArgs(RECIPE, data, ":services:java:warm-echo", List.of("build-metadata"),
                source(false), Map.of("nativeParallelism", "2"))).containsExactly(
                "-PnanofaasBuildType=native", "-PnativeOptimization=2", "-PnativeParallelism=2");
    }

    @Test
    void rejectsArgumentsTheDockerfileWouldSplit() throws IOException {
        JsonNode data = data("controlPlane: {modules: [], build: {mode: native, builder: container}}\n");

        assertThatThrownBy(() -> RecipeContainerBuild.gradleArgs(RECIPE, data, ":control-plane", List.of(),
                NullNode.getInstance(), Map.of("nativeBuildMemory", "6 g")))
                .isInstanceOf(GradleException.class)
                .hasMessageContaining("-PnativeBuildMemory=6 g")
                .hasMessageContaining("whitespace");
    }

    @Test
    void commandTargetsTheExportStage() {
        Path root = Path.of("/repo");

        assertThat(RecipeContainerBuild.command("docker", root, Path.of("/out/control-plane"), ":control-plane:nativeCompile",
                "platform/control-plane/build/native/nativeCompile/control-plane", "oracle",
                List.of("-PnanofaasBuildType=native", "-PcontrolPlaneModules=none"), null)).containsExactly(
                "docker", "build", "-f", "/repo/deploy/native-java/Dockerfile", "--target", "native-executable",
                "--output", "type=local,dest=/out/control-plane",
                "--build-context", "containerd_maven_repo=/repo/deploy/native-java/empty-maven-repo",
                "--build-arg", "NATIVE_TASK=:control-plane:nativeCompile",
                "--build-arg", "NATIVE_BINARY=platform/control-plane/build/native/nativeCompile/control-plane",
                "--build-arg", "GRAALVM_DISTRIBUTION=oracle",
                "--build-arg", "GRADLE_ARGS=-PnanofaasBuildType=native -PcontrolPlaneModules=none",
                "/repo");
    }

    @Test
    void anOutputPathWithACommaIsQuotedForTheCsvOutputOption() {
        List<String> command = RecipeContainerBuild.command("docker", Path.of("/repo"), Path.of("/out/a,b \"c\"/cp"),
                ":control-plane:nativeCompile", "bin", "community", List.of("-PnanofaasBuildType=native"), null);

        // docker reads --output as one CSV record: a field holding a comma or a quote is quoted, quotes doubled.
        assertThat(command.get(command.indexOf("--output") + 1)).isEqualTo("type=local,\"dest=/out/a,b \"\"c\"\"/cp\"");
    }

    @Test
    void containerdNeedsTheStagedRepository() {
        List<String> modules = List.of("containerd-deployment-provider");

        assertThatThrownBy(() -> RecipeContainerBuild.requireContainerdRepository(RECIPE, modules, null, null))
                .hasMessageContaining("-PcontainerdMavenLocal=true").hasMessageContaining("-Dmaven.repo.local");
        assertThatThrownBy(() -> RecipeContainerBuild.requireContainerdRepository(RECIPE, modules, "true",
                dir.resolve("missing").toString())).hasMessageContaining(dir.resolve("missing").toString())
                .hasMessageContaining("is not a directory");
        RecipeContainerBuild.requireContainerdRepository(RECIPE, modules, "true", dir.toString());
        RecipeContainerBuild.requireContainerdRepository(RECIPE, List.of("async-queue"), null, null);
    }
}
