package it.unimib.datai.nanofaas.cli.http;

import java.util.Map;

public record RuntimeConfigPatchRequest(
        long expectedRevision,
        Map<String, Object> values
) {}
