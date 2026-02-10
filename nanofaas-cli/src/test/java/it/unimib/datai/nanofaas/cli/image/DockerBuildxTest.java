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
}
