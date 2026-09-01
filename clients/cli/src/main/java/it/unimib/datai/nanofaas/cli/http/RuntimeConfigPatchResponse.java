package it.unimib.datai.nanofaas.cli.http;

import java.util.List;

public record RuntimeConfigPatchResponse(
        long revision,
        RuntimeConfigSnapshot effectiveConfig,
        String appliedAt,
        String changeId,
        List<String> warnings
) {}
