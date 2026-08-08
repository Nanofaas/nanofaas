package it.unimib.datai.nanofaas.modules.k8s.imagevalidation;

import it.unimib.datai.nanofaas.controlplane.registry.ImageValidator;
import it.unimib.datai.nanofaas.modules.k8s.config.KubernetesProperties;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class KubernetesImageValidatorConfigurationTest {

    @Test
    void kubernetesModuleProvidesTheKubernetesImageValidator() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(KubernetesProperties.class, () -> new KubernetesProperties("nanofaas", null));
            context.register(KubernetesImageValidatorConfiguration.class);
            context.refresh();

            assertThat(context.getBean(ImageValidator.class)).isInstanceOf(KubernetesImageValidator.class);
        }
    }
}
