package it.unimib.datai.nanofaas.modules.k8s;

import it.unimib.datai.nanofaas.common.controlplane.ControlPlaneModule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class KubernetesDeploymentProviderModuleTest {

    private static final String MODULE_CLASS = "it.unimib.datai.nanofaas.modules.k8s.KubernetesDeploymentProviderModule";

    @Test
    void serviceLoaderDiscoversKubernetesDeploymentProviderModule() {
        List<String> modules = ServiceLoader.load(ControlPlaneModule.class)
                .stream()
                .map(provider -> provider.type().getName())
                .toList();

        assertThat(modules).contains(MODULE_CLASS);
    }

    @Test
    void moduleConfigurationClassesStayWithinK8sModulePackages() {
        ControlPlaneModule module = ServiceLoader.load(ControlPlaneModule.class)
                .stream()
                .filter(provider -> MODULE_CLASS.equals(provider.type().getName()))
                .findFirst()
                .orElseThrow()
                .get();

        assertThat(module.configurationClasses())
                .isNotEmpty()
                .allSatisfy(configurationClass ->
                        assertThat(configurationClass.getName()).startsWith("it.unimib.datai.nanofaas.modules.k8s."));
    }
}
