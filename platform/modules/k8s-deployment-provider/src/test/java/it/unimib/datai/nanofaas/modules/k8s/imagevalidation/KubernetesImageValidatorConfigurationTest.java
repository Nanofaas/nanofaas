package it.unimib.datai.nanofaas.modules.k8s.imagevalidation;

import it.unimib.datai.nanofaas.controlplane.registry.ImageValidator;
import it.unimib.datai.nanofaas.modules.k8s.KubernetesDeploymentProviderConfiguration;
import it.unimib.datai.nanofaas.modules.k8s.config.KubernetesProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    /** The chart and deploy/k8s leave the backend empty: k8s is then the only provider this build has. */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "K8S"})
    void anUnsetOrCaseInsensitiveBackendStillSelectsTheKubernetesImageValidator(String backend) {
        new ApplicationContextRunner()
                .withUserConfiguration(KubernetesDeploymentProviderConfiguration.class)
                .withPropertyValues("nanofaas.deployment.default-backend=" + backend)
                .run(context -> assertThat(context).hasSingleBean(KubernetesImageValidator.class));
    }

    @Test
    void aMissingBackendPropertySelectsTheKubernetesImageValidator() {
        new ApplicationContextRunner()
                .withUserConfiguration(KubernetesDeploymentProviderConfiguration.class)
                .run(context -> assertThat(context).hasSingleBean(KubernetesImageValidator.class));
    }

    @Test
    void anotherBackendLeavesTheKubernetesImageValidatorOut() {
        new ApplicationContextRunner()
                .withUserConfiguration(KubernetesDeploymentProviderConfiguration.class)
                .withPropertyValues("nanofaas.deployment.default-backend=container-local")
                .run(context -> assertThat(context).doesNotHaveBean(KubernetesImageValidator.class));
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
