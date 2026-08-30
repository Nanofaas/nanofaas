package it.unimib.datai.nanofaas.modules.buildmetadata;

import java.util.List;

/**
 * Build and runtime identity of this control-plane artifact, as served by
 * {@code GET /modules/build-metadata}. Every field is best-effort: a value
 * absent at build or runtime time is {@code null} rather than omitted.
 */
public record BuildMetadata(
        String version,
        String revision,
        Boolean dirty,
        List<String> modules,
        Build build,
        Runtime runtime) {

    public record Build(String type, String variant, String optimization, BaseImages baseImages) {
    }

    public record BaseImages(String builder, String runtime) {
    }

    public record Runtime(String architecture, String kernelVersion, String javaVersion, String vm,
                           List<String> garbageCollectors) {
    }
}
