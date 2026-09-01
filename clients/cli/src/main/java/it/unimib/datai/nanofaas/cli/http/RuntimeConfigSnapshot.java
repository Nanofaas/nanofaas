package it.unimib.datai.nanofaas.cli.http;

import java.util.Map;

public record RuntimeConfigSnapshot(
        long revision,
        Map<String, Map<String, Object>> namespaces
) {}
