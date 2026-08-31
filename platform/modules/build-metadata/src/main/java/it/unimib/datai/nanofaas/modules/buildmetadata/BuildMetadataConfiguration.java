package it.unimib.datai.nanofaas.modules.buildmetadata;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ImportRuntimeHints;

@AutoConfiguration
@ImportRuntimeHints(BuildMetadataConfiguration.BuildPropertiesResourceHints.class)
public class BuildMetadataConfiguration {

    @Bean
    BuildMetadataController buildMetadataController() {
        return new BuildMetadataController(new BuildMetadataProvider());
    }

    // Spring Boot AOT processing overwrites static resource-config.json files placed
    // in META-INF/native-image/, so we register this resource via RuntimeHintsRegistrar
    // instead, which gets properly merged with the AOT-generated configuration.
    // Without this, native images silently report null build identity.
    static class BuildPropertiesResourceHints implements RuntimeHintsRegistrar {
        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
            hints.resources().registerPattern("META-INF/nanofaas-build.properties");
        }
    }
}
