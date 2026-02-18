package it.unimib.datai.nanofaas.modules.autoscaler;

import it.unimib.datai.nanofaas.common.controlplane.ControlPlaneModule;

import java.util.Set;

public final class AutoscalerModule implements ControlPlaneModule {
    @Override
    public Set<Class<?>> configurationClasses() {
        return Set.of(AutoscalerConfiguration.class);
    }
}
