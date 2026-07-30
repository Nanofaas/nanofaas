package it.unimib.datai.nanofaas.cli.image;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContainerBuildTest {

    @ParameterizedTest
    @ValueSource(strings = {"docker", "podman"})
    void buildsExpectedCommandLine(String runtime) {
        BuildSpec spec = new BuildSpec(
                Path.of("."),
                Path.of("Dockerfile"),
                "linux/amd64",
                "docker".equals(runtime), // Only Docker supports push.
                Map.of("VERSION", "1.2.3")
        );

        List<String> cmd = DockerBuildx.toCommand("registry.example/echo:1", spec, runtime);

        if ("docker".equals(runtime)) {
            assertThat(cmd).containsExactly(
                    "docker", "buildx", "build",
                    "--push",
                    "--tag", "registry.example/echo:1",
                    "--platform", "linux/amd64",
                    "-f", "Dockerfile",
                    "--build-arg", "VERSION=1.2.3",
                    "."
            );
        } else {
            assertThat(cmd).containsExactly(
                    "podman", "build",
                    "--tag", "registry.example/echo:1",
                    "--platform", "linux/amd64",
                    "-f", "Dockerfile",
                    "--build-arg", "VERSION=1.2.3",
                    "."
            );

            assertThat(cmd).doesNotContain("buildx", "--push");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"docker", "podman"})
    void commandWithoutPushPlatformOrDockerfile(String runtime) {
        BuildSpec spec = new BuildSpec(
                Path.of("./app"),
                null,
                null,
                false,
                Map.of()
        );

        List<String> cmd = DockerBuildx.toCommand("my-image:1", spec, runtime);

        assertThat(cmd)
                .contains("--tag", "my-image:1")
                .doesNotContain("--push", "--platform", "-f");

        if ("docker".equals(runtime)) {
            assertThat(cmd).containsExactly(
                    "docker", "buildx", "build",
                    "--load",
                    "--tag", "my-image:1",
                    "./app"
            );
        } else {
            assertThat(cmd).containsExactly(
                    "podman", "build",
                    "--tag", "my-image:1",
                    "./app"
            );

            assertThat(cmd).doesNotContain("buildx", "--load");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"docker", "podman"})
    void commandWithBlankPlatformOmitsPlatformFlag(String runtime) {
        BuildSpec spec = new BuildSpec(
                Path.of("."),
                Path.of("Dockerfile"),
                "   ",
                "docker".equals(runtime),
                Map.of()
        );

        List<String> cmd = DockerBuildx.toCommand("img:3", spec, runtime);

        assertThat(cmd).doesNotContain("--platform");

        if ("docker".equals(runtime)) {
            assertThat(cmd).contains("--push");
        } else {
            assertThat(cmd).doesNotContain("--push").doesNotContain("buildx");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"docker", "podman"})
    void commandWithMultipleBuildArgs(String runtime) {
        BuildSpec spec = new BuildSpec(
                Path.of("./ctx"),
                null,
                null,
                false,
                Map.of("ARG1", "val1", "ARG2", "val2")
        );

        List<String> cmd = DockerBuildx.toCommand("img:4", spec, runtime);

        assertThat(cmd)
                .contains("--build-arg", "ARG1=val1")
                .contains("--build-arg", "ARG2=val2");

        if ("docker".equals(runtime)) {
            assertThat(cmd).contains("buildx", "build", "--load");
        } else {
            assertThat(cmd)
                    .contains("podman", "build")
                    .doesNotContain("buildx", "--load");
        }
    }

    @Test
    void podmanThrowsExceptionWhenPushIsRequested() {
        BuildSpec spec = new BuildSpec(
                Path.of("."),
                Path.of("Dockerfile"),
                "linux/amd64",
                true,
                Map.of()
        );

        assertThatThrownBy(() -> DockerBuildx.toCommand("img:test", spec, "podman"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("--push is not supported with podman runtime. Disable it or use docker runtime.");
    }

    @Test
    void rejectsUnknownRuntime() {
        BuildSpec spec = new BuildSpec(
                Path.of("."),
                null,
                null,
                false,
                Map.of()
        );

        assertThatThrownBy(() -> DockerBuildx.toCommand("img:test", spec, "containerd"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Unsupported container runtime: containerd");
    }
}
