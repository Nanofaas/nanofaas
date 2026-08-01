package it.unimib.datai.nanofaas.controlplane.config;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

/**
 * Caffeine selects its generated cache implementation at runtime via
 * Class.forName based on the builder options, so the native image needs an
 * explicit reflection entry for the variant our builders produce.
 *
 * <p>IdempotencyStore uses strong keys/values + expireAfterWrite, which maps
 * to the {@code SSW} variant. Any new Caffeine builder combination (e.g.
 * maximumSize, expireAfterAccess) maps to a different class and must be added
 * here, or the native control plane crashes at startup with
 * {@code ClassNotFoundException}.
 */
public class CaffeineRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.reflection().registerType(
                TypeReference.of("com.github.benmanes.caffeine.cache.SSW"),
                builder -> builder
                        .withMembers(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS)
                        .withField("FACTORY"));
    }
}
