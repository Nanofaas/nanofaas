package it.unimib.datai.nanofaas.modules.imagevalidator;

import it.unimib.datai.nanofaas.controlplane.config.CoreDefaults;
import it.unimib.datai.nanofaas.controlplane.config.KubernetesProperties;
import it.unimib.datai.nanofaas.controlplane.registry.ImageValidator;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class ImageValidatorConfigurationTest {

    @Test
    void moduleBeanOverridesCoreDefaultWhenKubernetesPropertiesPresent() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(KubernetesProperties.class, () -> new KubernetesProperties("nanofaas", null));
            context.register(CoreDefaults.class, ImageValidatorConfiguration.class);
            context.refresh();

            ImageValidator imageValidator = context.getBean(ImageValidator.class);
            assertThat(imageValidator).isInstanceOf(KubernetesImageValidator.class);
            assertThat(context.getBeansOfType(ImageValidator.class)).containsKey("moduleImageValidator");
        }
    }

    @Test
    void moduleBeanIsNotCreatedWithoutKubernetesProperties() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(CoreDefaults.class, ImageValidatorConfiguration.class);
            context.refresh();

            assertThat(context.getBean(ImageValidator.class)).isSameAs(ImageValidator.noOp());
            assertThat(context.getBeansOfType(KubernetesImageValidator.class)).isEmpty();
        }
    }
}
