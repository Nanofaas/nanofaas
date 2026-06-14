package it.unimib.datai.nanofaas.modules.k8s.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "nanofaas.k8s")
public record KubernetesProperties(
        String namespace,
        String callbackUrl,
        String imagePullPolicy
) {
    public KubernetesProperties(String namespace, String callbackUrl) {
        this(namespace, callbackUrl, null);
    }

    @ConstructorBinding
    public KubernetesProperties {
        if (imagePullPolicy == null || imagePullPolicy.isBlank()) {
            imagePullPolicy = "Always";
        }
    }
}
