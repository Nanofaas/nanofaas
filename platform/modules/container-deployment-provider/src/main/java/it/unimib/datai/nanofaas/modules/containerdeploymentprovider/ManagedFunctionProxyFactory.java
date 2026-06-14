package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

public interface ManagedFunctionProxyFactory {
    ManagedFunctionProxy create(String functionName);
}
