package it.unimib.datai.nanofaas.modules.imagevalidator;

import io.fabric8.kubernetes.client.KubernetesClient;
import it.unimib.datai.nanofaas.controlplane.config.KubernetesProperties;
import it.unimib.datai.nanofaas.controlplane.registry.ImageValidator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnBean(KubernetesProperties.class)
public class ImageValidatorConfiguration {

    @Bean
    ImageValidator moduleImageValidator(ObjectProvider<KubernetesClient> clientProvider,
                                        KubernetesProperties properties) {
        return new KubernetesImageValidator(clientProvider, properties);
    }
}
