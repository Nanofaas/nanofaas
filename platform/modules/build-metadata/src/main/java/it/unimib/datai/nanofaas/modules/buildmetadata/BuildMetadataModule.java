package it.unimib.datai.nanofaas.modules.buildmetadata;

import it.unimib.datai.nanofaas.common.controlplane.ControlPlaneModule;

import java.util.Set;

public final class BuildMetadataModule implements ControlPlaneModule {
    @Override
    public Set<Class<?>> configurationClasses() {
        return Set.of(BuildMetadataConfiguration.class);
    }
}
