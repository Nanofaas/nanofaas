package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import java.util.List;

public interface ContainerRuntimeAdapter {
    boolean isAvailable();

    void pullImage(String image);

    void runContainer(ContainerInstanceSpec spec);

    void removeContainer(String containerName);

    List<ManagedContainer> listManagedContainers(String functionName);
}
