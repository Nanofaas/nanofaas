package it.unimib.datai.nanofaas.modules.k8s;

import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentProvider;
import it.unimib.datai.nanofaas.modules.k8s.config.KubernetesProperties;
import it.unimib.datai.nanofaas.modules.k8s.deployment.KubernetesManagedDeploymentProvider;
import it.unimib.datai.nanofaas.modules.k8s.dispatch.KubernetesResourceManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class KubernetesDeploymentProviderConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(KubernetesDeploymentProviderConfiguration.class)
            .withPropertyValues(
                    "nanofaas.k8s.namespace=test-ns",
                    "nanofaas.k8s.callback-url=http://control-plane.test:8080/v1/internal/executions"
            );

    @Test
    void configuration_registersPropertiesAndManagedProvider() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(KubernetesProperties.class);
            assertThat(context).hasSingleBean(KubernetesResourceManager.class);
            assertThat(context).hasSingleBean(ManagedDeploymentProvider.class);
            assertThat(context).hasSingleBean(KubernetesManagedDeploymentProvider.class);
            assertThat(context.getBean(KubernetesProperties.class).namespace()).isEqualTo("test-ns");
        });
    }
}
