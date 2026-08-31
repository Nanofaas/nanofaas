package it.unimib.datai.nanofaas.cli.http;

import java.util.List;

/**
 * Build and runtime identity of a control-plane artifact, as served by
 * {@code GET /modules/build-metadata}.
 */
public record BuildMetadata(
        String version,
        String revision,
        Boolean dirty,
        List<String> modules,
        Build build,
        Runtime runtime
) {
    public record Build(String type, String variant, String optimization, BaseImages baseImages) {}
    public record BaseImages(String builder, String runtime) {}
    public record Runtime(String architecture, String kernelVersion, String javaVersion,
                          String vm, List<String> garbageCollectors) {}
}
