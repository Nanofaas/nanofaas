package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

final class RoundRobinFunctionProxyFactory implements ManagedFunctionProxyFactory {

    private final String bindHost;
    private final ContainerProxyProperties properties;

    RoundRobinFunctionProxyFactory(String bindHost) {
        this(bindHost, ContainerProxyProperties.defaults());
    }

    RoundRobinFunctionProxyFactory(String bindHost, ContainerProxyProperties properties) {
        this.bindHost = bindHost;
        this.properties = properties;
    }

    @Override
    public ManagedFunctionProxy create(String functionName) {
        return new RoundRobinFunctionProxy(bindHost, properties);
    }
}
