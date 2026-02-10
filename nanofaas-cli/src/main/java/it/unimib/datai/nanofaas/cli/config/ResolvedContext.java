package it.unimib.datai.nanofaas.cli.config;

public record ResolvedContext(
        String contextName,
        String endpoint,
        String namespace
) {
}
