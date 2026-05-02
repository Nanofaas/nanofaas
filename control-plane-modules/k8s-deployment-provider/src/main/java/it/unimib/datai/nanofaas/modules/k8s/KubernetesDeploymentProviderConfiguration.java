package it.unimib.datai.nanofaas.modules.k8s;

import it.unimib.datai.nanofaas.modules.k8s.config.KubernetesProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan(basePackageClasses = KubernetesDeploymentProviderConfiguration.class)
@EnableConfigurationProperties(KubernetesProperties.class)
public class KubernetesDeploymentProviderConfiguration {
}
