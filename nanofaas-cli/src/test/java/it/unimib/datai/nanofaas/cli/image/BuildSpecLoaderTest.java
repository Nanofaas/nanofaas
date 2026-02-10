package it.unimib.datai.nanofaas.cli.image;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BuildSpecLoaderTest {

    @TempDir
    Path tmp;

    @Test
    void extractsXCliBuildFromYaml() throws Exception {
        Path p = tmp.resolve("function.yaml");
        Files.writeString(p, """
                name: echo
                image: registry.example/echo:1
                x-cli:
                  build:
                    context: ./fn
                    dockerfile: Dockerfile
                    platform: linux/amd64
                    push: true
                    buildArgs:
                      VERSION: \"1.2.3\"
                """);

        BuildSpec spec = BuildSpecLoader.load(p);

        assertThat(spec.context()).isEqualTo(Path.of("./fn"));
        assertThat(spec.dockerfile()).isEqualTo(Path.of("Dockerfile"));
        assertThat(spec.platform()).isEqualTo("linux/amd64");
        assertThat(spec.push()).isTrue();
        assertThat(spec.buildArgs()).isEqualTo(Map.of("VERSION", "1.2.3"));
    }
}
