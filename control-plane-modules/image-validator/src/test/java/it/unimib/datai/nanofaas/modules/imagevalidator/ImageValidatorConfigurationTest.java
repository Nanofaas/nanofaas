package it.unimib.datai.nanofaas.modules.imagevalidator;

import it.unimib.datai.nanofaas.modules.k8s.config.KubernetesProperties;
import it.unimib.datai.nanofaas.controlplane.registry.ImageValidator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;

import static org.assertj.core.api.Assertions.assertThat;

class ImageValidatorConfigurationTest {

    @Test
    void moduleBeanOverridesCoreDefaultWhenKubernetesPropertiesPresent() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(KubernetesProperties.class, () -> new KubernetesProperties("nanofaas", null));
            context.register(DefaultImageValidatorConfiguration.class, ImageValidatorConfiguration.class);
            context.refresh();

            ImageValidator imageValidator = context.getBean(ImageValidator.class);
            assertThat(imageValidator).isInstanceOf(KubernetesImageValidator.class);
            assertThat(context.getBeansOfType(ImageValidator.class)).containsKey("moduleImageValidator");
        }
    }

    @Test
    void moduleBeanIsNotCreatedWithoutKubernetesProperties() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(DefaultImageValidatorConfiguration.class, ImageValidatorConfiguration.class);
            context.refresh();

            assertThat(context.getBean(ImageValidator.class)).isSameAs(ImageValidator.noOp());
            assertThat(context.getBeansOfType(KubernetesImageValidator.class)).isEmpty();
        }
    }

    @Configuration
    static class DefaultImageValidatorConfiguration {
        @Bean
        @Fallback
        @ConditionalOnMissingBean(ImageValidator.class)
        ImageValidator imageValidator() {
            return ImageValidator.noOp();
        }
    }
}
