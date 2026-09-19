package it.unimib.datai.nanofaas.containerdeployment;

import java.util.List;

public interface ContainerRuntimeAdapter {
    boolean isAvailable();

    void pullImage(String image);

    ManagedContainer runContainer(ContainerInstanceSpec spec);

    void removeContainer(String containerName);

    List<ManagedContainer> listManagedContainers(String functionName);
}
