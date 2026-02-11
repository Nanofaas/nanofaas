package it.unimib.datai.nanofaas.cli.io;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YamlIOTest {

    @TempDir
    Path tmp;

    @Test
    void readValidYamlReturnsParsedObject() throws Exception {
        Path p = tmp.resolve("function.yaml");
        Files.writeString(p, """
                name: echo
                image: registry.example/echo:1
                timeoutMs: 5000
                """);

        FunctionSpec spec = YamlIO.read(p, FunctionSpec.class);

        assertThat(spec.name()).isEqualTo("echo");
        assertThat(spec.image()).isEqualTo("registry.example/echo:1");
        assertThat(spec.timeoutMs()).isEqualTo(5000);
    }

    @Test
    void readNonExistentFileThrowsUncheckedIOException() {
        Path p = tmp.resolve("nonexistent.yaml");

        assertThatThrownBy(() -> YamlIO.read(p, FunctionSpec.class))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("Failed to read YAML");
    }
}
