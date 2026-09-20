package it.unimib.datai.nanofaas.containerdeployment;

public final class RoundRobinFunctionProxyFactory implements ManagedFunctionProxyFactory {

    private final String bindHost;
    private final ProxySettings properties;

    public RoundRobinFunctionProxyFactory(String bindHost) {
        this(bindHost, ProxySettings.defaults());
    }

    public RoundRobinFunctionProxyFactory(String bindHost, ProxySettings properties) {
        this.bindHost = bindHost;
        this.properties = properties;
    }

    @Override
    public ManagedFunctionProxy create(String functionName) {
        return new RoundRobinFunctionProxy(bindHost, properties);
    }
}
