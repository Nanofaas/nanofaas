package it.unimib.datai.nanofaas.controlplane.registry;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "nanofaas.registry")
public record FunctionCatalogProperties(@DefaultValue("build/nanofaas/functions.json") Path path) {
}
