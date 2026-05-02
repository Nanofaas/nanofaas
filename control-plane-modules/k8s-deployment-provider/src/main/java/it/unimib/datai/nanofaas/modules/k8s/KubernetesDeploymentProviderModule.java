package it.unimib.datai.nanofaas.modules.k8s;

import it.unimib.datai.nanofaas.common.controlplane.ControlPlaneModule;

import java.util.Set;

public final class KubernetesDeploymentProviderModule implements ControlPlaneModule {
    @Override
    public Set<Class<?>> configurationClasses() {
        return Set.of(KubernetesDeploymentProviderConfiguration.class);
    }
}
