package it.unimib.datai.nanofaas.gradle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecipeOutputTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DIGEST = "sha256:" + "a".repeat(64);

    private static boolean valid(ObjectNode image) {
        ObjectNode report = JSON.createObjectNode().put("schemaVersion", 2).put("tag", "1.0.0");
        report.putObject("recipe").put("name", "demo").put("sha256", "0".repeat(64)).put("schemaVersion", 2);
        report.putNull("source");
        report.putArray("modules");
        report.putArray("components").addObject().put("kind", "control-plane").put("name", "control-plane")
                .put("sdk", "java").put("mode", "jvm").put("artifact", "control-plane/").set("image", image);
        return RecipeOutput.validReport(report);
    }

    private static ObjectNode image(String status) {
        return JSON.createObjectNode().put("reference", "r.example/cp:1.0.0").put("status", status);
    }

    private static ObjectNode buildx(String status) {
        ObjectNode image = image(status);
        image.putArray("platforms").add("linux/amd64").add("linux/arm64");
        return image.put("provenance", true);
    }

    @Test
    void aClassicV2ImageNeedsItsId() {
        assertThat(valid(image("built"))).isFalse();
        assertThat(valid(image("built").put("id", DIGEST))).isTrue();
    }

    @Test
    void aBuildxImageNeedsNoIdUntilPublished() {
        assertThat(valid(buildx("built"))).isTrue();
        assertThat(valid(buildx("failed"))).isTrue();
        assertThat(valid(buildx("published-unverified"))).isTrue();
        ObjectNode empty = image("built");
        empty.putArray("platforms");
        assertThat(valid(empty)).as("an empty platforms list is not the buildx path").isFalse();
    }

    @Test
    void aPublishedBuildxImageNeedsItsDigestAndEveryPlatform() {
        ObjectNode complete = buildx("published").put("digest", DIGEST);
        complete.putObject("manifests").put("linux/amd64", DIGEST).put("linux/arm64", DIGEST);
        assertThat(valid(complete)).isTrue();

        assertThat(valid(buildx("published").put("digest", DIGEST))).as("no manifests").isFalse();
        ObjectNode partial = buildx("published").put("digest", DIGEST);
        partial.putObject("manifests").put("linux/amd64", DIGEST);
        assertThat(valid(partial)).as("a platform missing").isFalse();
        ObjectNode other = buildx("published").put("digest", DIGEST);
        other.putObject("manifests").put("linux/amd64", DIGEST).put("linux/riscv64", DIGEST);
        assertThat(valid(other)).as("another platform").isFalse();
        ObjectNode notADigest = buildx("published").put("digest", DIGEST);
        notADigest.putObject("manifests").put("linux/amd64", DIGEST).put("linux/arm64", "latest");
        assertThat(valid(notADigest)).as("a tag instead of a digest").isFalse();
        ObjectNode noDigest = buildx("published");
        noDigest.putObject("manifests").put("linux/amd64", DIGEST).put("linux/arm64", DIGEST);
        assertThat(valid(noDigest)).as("no index digest").isFalse();
    }
}
