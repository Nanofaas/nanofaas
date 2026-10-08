package it.unimib.datai.nanofaas.gradle;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RecipeBuildxTest {

    private static final String INDEX = "sha256:" + "1".repeat(64);
    private static final String AMD = "sha256:" + "a".repeat(64);
    private static final String ARM = "sha256:" + "b".repeat(64);
    private static final String ATTESTATION = "sha256:" + "c".repeat(64);

    @TempDir
    Path dir;

    private JsonNode data(String registry) throws IOException {
        Path file = dir.resolve("recipe.yaml");
        Files.writeString(file, "schemaVersion: 2\nname: demo\n" + registry
                + "controlPlane: {modules: [], build: {mode: jvm}}\n");
        return new RecipeReader().read(file, null).data();
    }

    private static RecipeTasks.Target target(String mode, String image, String builder) {
        return new RecipeTasks.Target("controlPlane", "control-plane", "control-plane", "java", mode,
                ":control-plane:" + (mode.equals("native") ? "nativeCompile" : "bootJar"), null, null, "control-plane/",
                image, null, null, builder == null ? null : new RecipeBuildProperties.NativeOptions("3", "serial",
                        List.of(), builder, builder.equals("container") ? "community" : null));
    }

    @Test
    void platformsAndProvenanceComeFromTheRegistry() throws IOException {
        JsonNode multi = data("registry: {repository: r.example, tag: t, platforms: [linux/amd64, linux/arm64],"
                + " provenance: true}\n");
        assertThat(RecipeBuildx.platforms(multi)).containsExactly("linux/amd64", "linux/arm64");
        assertThat(RecipeBuildx.provenance(multi)).isTrue();

        JsonNode classic = data("registry: {repository: r.example, tag: t}\n");
        assertThat(RecipeBuildx.platforms(classic)).isNull();
        assertThat(RecipeBuildx.provenance(classic)).isFalse();
        assertThat(RecipeBuildx.platforms(data(""))).isNull();
    }

    @Test
    void hostPlatformNamesOnlyTheArchitecturesImagesTarget() {
        assertThat(RecipeBuildx.hostPlatform("aarch64")).isEqualTo("linux/arm64");
        assertThat(RecipeBuildx.hostPlatform("arm64")).isEqualTo("linux/arm64");
        assertThat(RecipeBuildx.hostPlatform("amd64")).isEqualTo("linux/amd64");
        assertThat(RecipeBuildx.hostPlatform("x86_64")).isEqualTo("linux/amd64");
        assertThat(RecipeBuildx.hostPlatform("ppc64le")).isNull();
    }

    @Test
    void hostBuiltNativeImagesMayOnlyTargetTheHostPlatform() {
        RecipeTasks.Target hostNative = target("native", "img", "host");

        assertThat(RecipeBuildx.platformProblem("aarch64", List.of("linux/arm64"), hostNative)).isNull();
        assertThat(RecipeBuildx.platformProblem("aarch64", List.of("linux/amd64"), hostNative))
                .contains("linux/arm64").contains("builder: host").contains("use build.builder: container");
        assertThat(RecipeBuildx.platformProblem("aarch64", List.of("linux/amd64", "linux/arm64"), hostNative))
                .contains("use build.builder: container");
        assertThat(RecipeBuildx.platformProblem("ppc64le", List.of("linux/arm64"), hostNative)).contains("ppc64le");
        assertThat(RecipeBuildx.platformProblem("aarch64", null, hostNative)).isNull();
        assertThat(RecipeBuildx.platformProblem("aarch64", List.of("linux/amd64"), target("native", "img", "container")))
                .isNull();
        assertThat(RecipeBuildx.platformProblem("aarch64", List.of("linux/amd64"), target("native", null, "host")))
                .isNull();
        assertThat(RecipeBuildx.platformProblem("aarch64", List.of("linux/amd64"), target("jvm", "img", null))).isNull();
    }

    @Test
    void builderPlatformsJoinEveryNodeAndDropTheConfiguredMark() {
        String output = """
                Name:          multi
                Driver:        docker-container

                Nodes:
                Name:                  multi0
                Platforms:             linux/arm64, linux/arm/v7*
                Name:                  multi1
                Platforms:             linux/amd64*, linux/amd64/v2
                """;

        assertThat(RecipeBuildx.builderPlatforms(output))
                .containsExactly("linux/arm64", "linux/arm/v7", "linux/amd64", "linux/amd64/v2");
        assertThat(RecipeBuildx.builderPlatforms("Name: empty\n")).isEmpty();
    }

    @Test
    void commandsCarryBuilderPlatformsProvenanceAndPush() {
        assertThat(RecipeBuildx.inspect("docker", null)).containsExactly("docker", "buildx", "inspect", "--bootstrap");
        assertThat(RecipeBuildx.inspect("docker", "multi"))
                .containsExactly("docker", "buildx", "inspect", "--bootstrap", "--builder", "multi");

        List<String> source = List.of("-f", "/repo/tools/gradle-plugin/dockerfiles/Dockerfile.jvm", "/out/control-plane");
        assertThat(RecipeBuildx.build("docker", null, List.of("linux/amd64", "linux/arm64"), true, "r.example/cp:1",
                source, null)).containsExactly("docker", "buildx", "build", "--platform", "linux/amd64,linux/arm64",
                "--provenance=mode=max", "-t", "r.example/cp:1", "-f", "/repo/tools/gradle-plugin/dockerfiles/Dockerfile.jvm",
                "/out/control-plane");
        assertThat(RecipeBuildx.build("docker", "multi", List.of("linux/arm64"), false, "r.example/cp:1", source,
                Path.of("/tmp/meta.json"))).containsExactly("docker", "buildx", "build", "--builder", "multi",
                "--platform", "linux/arm64", "--provenance=false", "-t", "r.example/cp:1", "--push", "--metadata-file",
                "/tmp/meta.json", "-f", "/repo/tools/gradle-plugin/dockerfiles/Dockerfile.jvm", "/out/control-plane");
    }

    @Test
    void imagetoolsInspectsTheRepositoryByDigestEvenBehindARegistryPort() {
        assertThat(RecipeBuildx.imagetools("docker", "registry.example:5000/team/cp:1.0.0", INDEX))
                .containsExactly("docker", "buildx", "imagetools", "inspect", "registry.example:5000/team/cp@" + INDEX, "--raw");
    }

    @Test
    void pushedDigestReadsTheMetadataFile() {
        assertThat(RecipeBuildx.pushedDigest("{\"containerimage.digest\": \"" + INDEX + "\", \"image.name\": \"r/cp:1\"}"))
                .isEqualTo(INDEX);
        assertThat(RecipeBuildx.pushedDigest("{\"image.name\": \"r/cp:1\"}")).isNull();
        assertThat(RecipeBuildx.pushedDigest("{\"containerimage.digest\": \"latest\"}")).isNull();
        assertThat(RecipeBuildx.pushedDigest("")).isNull();
        assertThat(RecipeBuildx.pushedDigest("not json")).isNull();
    }

    @Test
    void manifestsMapPlatformsIgnoreVariantsAndSkipAttestations() {
        String index = """
                {"schemaVersion": 2, "mediaType": "application/vnd.oci.image.index.v1+json", "manifests": [
                  {"digest": "%s", "platform": {"architecture": "amd64", "os": "linux"}},
                  {"digest": "%s", "platform": {"architecture": "arm64", "os": "linux", "variant": "v8"}},
                  {"digest": "%s", "annotations": {"vnd.docker.reference.type": "attestation-manifest"},
                   "platform": {"architecture": "unknown", "os": "unknown"}}]}
                """.formatted(AMD, ARM, ATTESTATION);

        assertThat(RecipeBuildx.manifests(index, INDEX, List.of("linux/amd64", "linux/arm64")))
                .containsExactly(Map.entry("linux/amd64", AMD), Map.entry("linux/arm64", ARM));
    }

    @Test
    void aSingleManifestIsTheOnlyPlatform() {
        String manifest = "{\"schemaVersion\": 2, \"config\": {\"digest\": \"" + AMD + "\"}, \"layers\": []}";

        assertThat(RecipeBuildx.manifests(manifest, INDEX, List.of("linux/arm64")))
                .containsExactly(Map.entry("linux/arm64", INDEX));
        assertThat(RecipeBuildx.manifests(manifest, INDEX, List.of("linux/amd64", "linux/arm64"))).isNull();
        assertThat(RecipeBuildx.manifests("", INDEX, List.of("linux/arm64"))).isNull();
        assertThat(RecipeBuildx.manifests("{\"errors\": []}", INDEX, List.of("linux/arm64"))).isNull();
    }

    @Test
    void coversNeedsExactlyThePlatformsWithDigests() {
        Map<String, String> both = new LinkedHashMap<>(Map.of("linux/amd64", AMD, "linux/arm64", ARM));
        assertThat(RecipeBuildx.covers(both, List.of("linux/amd64", "linux/arm64"))).isTrue();
        assertThat(RecipeBuildx.covers(Map.of("linux/amd64", AMD), List.of("linux/amd64", "linux/arm64"))).isFalse();
        assertThat(RecipeBuildx.covers(both, List.of("linux/amd64"))).isFalse();
        assertThat(RecipeBuildx.covers(Map.of("linux/amd64", "latest"), List.of("linux/amd64"))).isFalse();
        assertThat(RecipeBuildx.covers(null, List.of("linux/amd64"))).isFalse();
    }
}
