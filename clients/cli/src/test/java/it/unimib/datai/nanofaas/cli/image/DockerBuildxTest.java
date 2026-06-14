package it.unimib.datai.nanofaas.cli.image;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DockerBuildxTest {

    @Test
    void buildsExpectedCommandLine() {
        BuildSpec spec = new BuildSpec(
                Path.of("."),
                Path.of("Dockerfile"),
                "linux/amd64",
                true,
                Map.of("VERSION", "1.2.3")
        );

        List<String> cmd = DockerBuildx.toCommand("registry.example/echo:1", spec);

        assertThat(cmd).containsExactly(
                "docker", "buildx", "build",
                "--push",
                "--tag", "registry.example/echo:1",
                "--platform", "linux/amd64",
                "-f", "Dockerfile",
                "--build-arg", "VERSION=1.2.3",
                "."
        );
    }

    @Test
    void commandWithoutPushPlatformOrDockerfile() {
        BuildSpec spec = new BuildSpec(
                Path.of("./app"),
                null,       // no dockerfile
                null,       // no platform
                false,      // push = false
                Map.of()
        );

        List<String> cmd = DockerBuildx.toCommand("my-image:1", spec);

        assertThat(cmd).containsExactly(
                "docker", "buildx", "build",
                "--tag", "my-image:1",
                "./app"
        );
        assertThat(cmd).doesNotContain("--push", "--platform", "-f");
    }

    @Test
    void commandWithBlankPlatformOmitsPlatformFlag() {
        BuildSpec spec = new BuildSpec(
                Path.of("."),
                Path.of("Dockerfile"),
                "   ",   // blank platform → should be omitted
                true,
                Map.of()
        );

        List<String> cmd = DockerBuildx.toCommand("img:3", spec);

        assertThat(cmd).doesNotContain("--platform");
        assertThat(cmd).contains("--push");
    }

    @Test
    void commandWithMultipleBuildArgs() {
        BuildSpec spec = new BuildSpec(
                Path.of("./ctx"),
                null,
                null,
                false,
                Map.of("ARG1", "val1", "ARG2", "val2")
        );

        List<String> cmd = DockerBuildx.toCommand("img:4", spec);

        assertThat(cmd).contains("--build-arg", "ARG1=val1");
        assertThat(cmd).contains("--build-arg", "ARG2=val2");
        assertThat(cmd).doesNotContain("--push", "--platform", "-f");
    }

    @Test
    void commandWithPlatformButNoPush() {
        BuildSpec spec = new BuildSpec(
                Path.of("."),
                Path.of("Dockerfile.custom"),
                "linux/arm64",
                false,
                Map.of()
        );

        List<String> cmd = DockerBuildx.toCommand("img:2", spec);

        assertThat(cmd).contains("--platform", "linux/arm64");
        assertThat(cmd).contains("-f", "Dockerfile.custom");
        assertThat(cmd).doesNotContain("--push");
    }
}
