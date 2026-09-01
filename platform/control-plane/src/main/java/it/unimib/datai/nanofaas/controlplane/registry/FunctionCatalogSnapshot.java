package it.unimib.datai.nanofaas.controlplane.registry;

import java.util.List;

record FunctionCatalogSnapshot(int schemaVersion, List<RegisteredFunction> functions) {
    FunctionCatalogSnapshot {
        functions = List.copyOf(functions);
    }
}
