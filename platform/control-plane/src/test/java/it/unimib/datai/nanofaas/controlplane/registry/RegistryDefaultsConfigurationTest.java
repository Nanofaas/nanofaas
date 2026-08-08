package it.unimib.datai.nanofaas.controlplane.registry;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;

class RegistryDefaultsConfigurationTest {

    @Test
    void registersNoOpImageValidatorWhenMissing() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(RegistryDefaultsConfiguration.class);
            context.refresh();

            assertThatCode(() -> context.getBean(ImageValidator.class).validate(null)).doesNotThrowAnyException();
        }
    }

    @Test
    void doesNotOverrideExplicitImageValidator() {
        ImageValidator customImageValidator = spec -> {};

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ImageValidator.class, () -> customImageValidator);
            context.register(RegistryDefaultsConfiguration.class);
            context.refresh();

            assertThat(context.getBean(ImageValidator.class)).isSameAs(customImageValidator);
        }
    }
}
