package it.unimib.datai.nanofaas.modules.offload;

import it.unimib.datai.nanofaas.common.controlplane.ControlPlaneModule;

import java.util.Set;

public final class OffloadModule implements ControlPlaneModule {
    @Override
    public Set<Class<?>> configurationClasses() {
        return Set.of(OffloadConfiguration.class);
    }
}
