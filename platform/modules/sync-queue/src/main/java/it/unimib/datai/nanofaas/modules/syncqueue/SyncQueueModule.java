package it.unimib.datai.nanofaas.modules.syncqueue;

import it.unimib.datai.nanofaas.common.controlplane.ControlPlaneModule;

import java.util.Set;

public final class SyncQueueModule implements ControlPlaneModule {
    @Override
    public Set<Class<?>> configurationClasses() {
        return Set.of(SyncQueueConfiguration.class);
    }
}
