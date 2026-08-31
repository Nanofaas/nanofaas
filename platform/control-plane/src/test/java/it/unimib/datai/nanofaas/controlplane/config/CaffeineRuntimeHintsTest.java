package it.unimib.datai.nanofaas.controlplane.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeReference;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registrar used to name one variant by hand and the native image crashed at startup the
 * day a builder changed. Nothing caught it because nothing tested it, so these are the two
 * variants the control plane's own caches produce.
 */
class CaffeineRuntimeHintsTest {

    private static Set<String> registeredTypes() {
        RuntimeHints hints = new RuntimeHints();
        new CaffeineRuntimeHints().registerHints(hints, CaffeineRuntimeHintsTest.class.getClassLoader());
        return hints.reflection().typeHints()
                .map(hint -> hint.getType().getName())
                .collect(Collectors.toSet());
    }

    @Test
    void registersTheVariantsThisControlPlaneActuallyBuilds() {
        assertThat(registeredTypes())
                .as("SSW backs expireAfterWrite; SSMSA backs maximumSize with a variable expiry")
                .contains("com.github.benmanes.caffeine.cache.SSW",
                        "com.github.benmanes.caffeine.cache.SSMSA");
    }

    @Test
    void registersEveryGeneratedVariantSoABuilderChangeCannotBreakTheNativeImage() {
        // Caffeine 3.2.4 generates 528 of them; asserting the shape rather than the count keeps
        // this from failing on a routine dependency bump.
        assertThat(registeredTypes())
                .hasSizeGreaterThan(100)
                .allSatisfy(name -> assertThat(name)
                        .startsWith("com.github.benmanes.caffeine.cache."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SSW", "SSMSA"})
    void asksForTheFactoryFieldCaffeineLoadsReflectively(String variant) {
        RuntimeHints hints = new RuntimeHints();
        new CaffeineRuntimeHints().registerHints(hints, CaffeineRuntimeHintsTest.class.getClassLoader());

        var hint = hints.reflection()
                .getTypeHint(TypeReference.of("com.github.benmanes.caffeine.cache." + variant));

        assertThat(hint).isNotNull();
        assertThat(hint.fields())
                .as("LocalCacheFactory reads the static FACTORY handle off the generated class")
                .anySatisfy(field -> assertThat(field.getName()).isEqualTo("FACTORY"));
    }
}
