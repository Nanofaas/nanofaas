package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

public interface ContainerRuntimeAdapter {
    boolean isAvailable();

    void runContainer(ContainerInstanceSpec spec);

    void removeContainer(String containerName);
}
