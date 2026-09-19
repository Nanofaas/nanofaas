package it.unimib.datai.nanofaas.containerdeployment;

public interface ManagedFunctionProxyFactory {
    ManagedFunctionProxy create(String functionName);
}
