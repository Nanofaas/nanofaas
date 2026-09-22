package it.unimib.datai.nanofaas.controlplane;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ControlPlaneAutoConfigurationImportsTest {
    private static final Map<String, String> MODULE_CONFIGURATIONS = Map.ofEntries(
            Map.entry("async-queue", "it.unimib.datai.nanofaas.modules.asyncqueue.AsyncQueueConfiguration"),
            Map.entry("autoscaler", "it.unimib.datai.nanofaas.modules.autoscaler.AutoscalerConfiguration"),
            Map.entry("build-metadata", "it.unimib.datai.nanofaas.modules.buildmetadata.BuildMetadataConfiguration"),
            Map.entry("concurrency-control", "it.unimib.datai.nanofaas.modules.concurrencycontrol.ConcurrencyControlConfiguration"),
            Map.entry("container-deployment-provider", "it.unimib.datai.nanofaas.modules.containerdeploymentprovider.ContainerDeploymentProviderConfiguration"),
            Map.entry("containerd-deployment-provider", "it.unimib.datai.nanofaas.modules.containerddeploymentprovider.ContainerdDeploymentProviderConfiguration"),
            Map.entry("k8s-deployment-provider", "it.unimib.datai.nanofaas.modules.k8s.KubernetesDeploymentProviderConfiguration"),
            Map.entry("offload", "it.unimib.datai.nanofaas.modules.offload.OffloadConfiguration"),
            Map.entry("runtime-config", "it.unimib.datai.nanofaas.modules.runtimeconfig.RuntimeConfigConfiguration"),
            Map.entry("sync-queue", "it.unimib.datai.nanofaas.modules.syncqueue.SyncQueueConfiguration")
    );

    @Test
    void selectedModulesPublishSpringBootAutoConfigurations() {
        Set<String> selected = Arrays.stream(System.getProperty(
                        "nanofaas.selectedControlPlaneModules", "").split(","))
                .filter(module -> !module.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        Set<String> candidates = Set.copyOf(ImportCandidates.load(
                AutoConfiguration.class, Thread.currentThread().getContextClassLoader()).getCandidates());

        assertThat(candidates).containsAll(selected.stream()
                .map(MODULE_CONFIGURATIONS::get)
                .toList());
    }
}
