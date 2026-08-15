package it.unimib.datai.nanofaas.modules.concurrencycontrol;

import it.unimib.datai.nanofaas.common.controlplane.ControlPlaneModule;

import java.util.Set;

public final class ConcurrencyControlModule implements ControlPlaneModule {
    @Override
    public Set<Class<?>> configurationClasses() {
        return Set.of(ConcurrencyControlConfiguration.class);
    }
}
