package it.unimib.datai.nanofaas.containerdeployment;

import java.util.List;

public interface ContainerRuntimeAdapter {
    boolean isAvailable();

    void pullImage(String image);

    /**
     * Starts the requested container and returns its nonblank base URL, reachable from the control plane.
     * Ownership must remain discoverable if startup fails after creating a resource.
     */
    ManagedContainer runContainer(ContainerInstanceSpec spec);

    void removeContainer(String containerName);

    /**
     * Lists resources owned by the function, including stopped or partially removed containers.
     * A container awaiting cleanup must not be reported as running and adoptable.
     */
    List<ManagedContainer> listManagedContainers(String functionName);
}
