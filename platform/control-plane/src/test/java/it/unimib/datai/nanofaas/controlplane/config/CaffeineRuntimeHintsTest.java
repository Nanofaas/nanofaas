package it.unimib.datai.nanofaas.controlplane.config;

import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeReference;

import static org.assertj.core.api.Assertions.assertThat;

class CaffeineRuntimeHintsTest {

    @Test
    void registersFactoryFieldUsedByCaffeineNativeCache() {
        RuntimeHints hints = new RuntimeHints();

        new CaffeineRuntimeHints().registerHints(hints, getClass().getClassLoader());

        assertThat(hints.reflection()
                .getTypeHint(TypeReference.of("com.github.benmanes.caffeine.cache.SSW"))
                .fields())
                .extracting("name")
                .contains("FACTORY");
    }
}
