package it.unimib.datai.nanofaas.controlplane.config;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Registers Vert.x resource files for GraalVM native-image inclusion.
 * <p>
 * Spring Boot AOT processing overwrites static {@code resource-config.json} files
 * placed in {@code META-INF/native-image/}, so we use {@link RuntimeHintsRegistrar}
 * which gets properly merged with the AOT-generated configuration.
 * <p>
 * Without this, the native binary crashes at startup with:
 * {@code IllegalStateException: Cannot find vertx-version.txt on classpath}
 */
@Configuration
@ImportRuntimeHints(VertxRuntimeHints.VertxResourceHints.class)
public class VertxRuntimeHints {

    static class VertxResourceHints implements RuntimeHintsRegistrar {
        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
            hints.resources().registerPattern("META-INF/vertx/*");
        }
    }
}
