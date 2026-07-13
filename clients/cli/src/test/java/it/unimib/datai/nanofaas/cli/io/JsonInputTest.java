package it.unimib.datai.nanofaas.cli.io;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class JsonInputTest {
    @Test
    void readsJsonFromStandardInput() {
        JsonNode input = JsonInput.read(
                "@-",
                new ByteArrayInputStream("{\"message\":\"hello\"}".getBytes(StandardCharsets.UTF_8))
        );

        assertThat(input.path("message").asText()).isEqualTo("hello");
    }
}
