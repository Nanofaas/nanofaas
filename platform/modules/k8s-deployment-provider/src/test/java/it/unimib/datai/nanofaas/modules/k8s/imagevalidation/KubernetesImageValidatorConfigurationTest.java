package it.unimib.datai.nanofaas.modules.k8s.imagevalidation;

import it.unimib.datai.nanofaas.controlplane.registry.ImageValidator;
import it.unimib.datai.nanofaas.modules.k8s.KubernetesDeploymentProviderConfiguration;
import it.unimib.datai.nanofaas.modules.k8s.config.KubernetesProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class KubernetesImageValidatorConfigurationTest {

    @Test
    void k8sBackendSelectsTheKubernetesImageValidator() {
        new ApplicationContextRunner()
                .withUserConfiguration(KubernetesDeploymentProviderConfiguration.class)
                .withPropertyValues("nanofaas.deployment.default-backend=k8s")
                .run(context -> assertThat(context).hasSingleBean(KubernetesImageValidator.class));
    }

    @Test
    void kubernetesModuleProvidesTheKubernetesImageValidator() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of("nanofaas.deployment.default-backend=k8s").applyTo(context);
            context.registerBean(KubernetesProperties.class, () -> new KubernetesProperties("nanofaas", null));
            context.register(KubernetesImageValidatorConfiguration.class);
            context.refresh();

            assertThat(context.getBean(ImageValidator.class)).isInstanceOf(KubernetesImageValidator.class);
        }
    }
}
