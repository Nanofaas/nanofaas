package it.unimib.datai.nanofaas.controlplane;

import it.unimib.datai.nanofaas.common.controlplane.ControlPlaneModule;

import java.util.Set;

public final class TestControlPlaneModule implements ControlPlaneModule {
    @Override
    public Set<Class<?>> configurationClasses() {
        return Set.of(TestModuleConfiguration.class);
    }
}
