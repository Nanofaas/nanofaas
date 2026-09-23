package it.unimib.datai.nanofaas.containerdeployment;

public final class RoundRobinFunctionProxyFactory {

    private final String bindHost;
    private final ProxySettings properties;

    public RoundRobinFunctionProxyFactory(String bindHost) {
        this(bindHost, ProxySettings.defaults());
    }

    public RoundRobinFunctionProxyFactory(String bindHost, ProxySettings properties) {
        this.bindHost = bindHost;
        this.properties = properties;
    }

    public ManagedFunctionProxy create(String functionName) { // NOSONAR (java:S1172): providers name the function they proxy
        return new RoundRobinFunctionProxy(bindHost, properties);
    }
}
