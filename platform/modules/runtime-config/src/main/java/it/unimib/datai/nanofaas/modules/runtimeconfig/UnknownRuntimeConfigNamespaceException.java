package it.unimib.datai.nanofaas.modules.runtimeconfig;

public class UnknownRuntimeConfigNamespaceException extends RuntimeException {
    public UnknownRuntimeConfigNamespaceException(String namespace) {
        super("Unknown runtime config namespace: " + namespace);
    }
}
