package it.unimib.datai.nanofaas.modules.k8s.imagevalidation;

import io.fabric8.kubernetes.client.KubernetesClient;
import it.unimib.datai.nanofaas.modules.k8s.config.KubernetesProperties;
import it.unimib.datai.nanofaas.controlplane.registry.ImageValidator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "nanofaas.deployment.default-backend", havingValue = "k8s")
public class KubernetesImageValidatorConfiguration {

    @Bean
    ImageValidator moduleImageValidator(ObjectProvider<KubernetesClient> clientProvider,
                                        KubernetesProperties properties) {
        return new KubernetesImageValidator(clientProvider, properties);
    }
}
