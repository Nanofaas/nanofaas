package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

final class RoundRobinFunctionProxyFactory implements ManagedFunctionProxyFactory {

    private final String bindHost;

    RoundRobinFunctionProxyFactory(String bindHost) {
        this.bindHost = bindHost;
    }

    @Override
    public ManagedFunctionProxy create(String functionName) {
        return new RoundRobinFunctionProxy(bindHost);
    }
}
