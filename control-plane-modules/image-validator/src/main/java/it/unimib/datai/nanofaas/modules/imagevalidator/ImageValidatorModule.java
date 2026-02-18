package it.unimib.datai.nanofaas.modules.imagevalidator;

import it.unimib.datai.nanofaas.common.controlplane.ControlPlaneModule;

import java.util.Set;

public final class ImageValidatorModule implements ControlPlaneModule {
    @Override
    public Set<Class<?>> configurationClasses() {
        return Set.of(ImageValidatorConfiguration.class);
    }
}
