package it.unimib.datai.nanofaas.modules.buildmetadata;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BuildMetadataConfiguration {

    @Bean
    BuildMetadataController buildMetadataController() {
        return new BuildMetadataController();
    }
}
