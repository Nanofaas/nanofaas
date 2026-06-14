package it.unimib.datai.nanofaas.common.controlplane;

import java.util.Set;

/**
 * Extension point for optional control-plane modules loaded via ServiceLoader.
 */
public interface ControlPlaneModule {

    /**
     * Human-readable module name used in startup logging.
     */
    default String name() {
        return getClass().getSimpleName();
    }

    /**
     * Spring configuration classes to add to the control-plane application sources.
     */
    Set<Class<?>> configurationClasses();
}
