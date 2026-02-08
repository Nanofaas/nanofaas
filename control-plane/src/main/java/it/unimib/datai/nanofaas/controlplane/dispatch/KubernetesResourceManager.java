package it.unimib.datai.nanofaas.controlplane.dispatch;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler;
import io.fabric8.kubernetes.client.KubernetesClient;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.ScalingStrategy;
import it.unimib.datai.nanofaas.controlplane.config.KubernetesProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class KubernetesResourceManager {
    private static final Logger log = LoggerFactory.getLogger(KubernetesResourceManager.class);

    private final ObjectProvider<KubernetesClient> clientProvider;
    private final KubernetesProperties properties;
    private final KubernetesDeploymentBuilder builder;
    private final String resolvedNamespace;

    public KubernetesResourceManager(ObjectProvider<KubernetesClient> clientProvider, KubernetesProperties properties) {
        this.clientProvider = clientProvider;
        this.properties = properties;
        this.builder = new KubernetesDeploymentBuilder(properties);
        this.resolvedNamespace = resolveNamespace(properties);
    }

    /**
     * Creates Deployment + Service (+ HPA if strategy=HPA) for a function.
     * Uses createOrReplace for idempotency (safe on control-plane restart).
     * Returns the service URL for invocations.
     */
    public String provision(FunctionSpec spec) {
        Deployment deployment = builder.buildDeployment(spec);
        io.fabric8.kubernetes.api.model.Service service = builder.buildService(spec);
        KubernetesClient client = clientProvider.getObject();

        client.apps().deployments()
                .inNamespace(resolvedNamespace)
                .resource(deployment)
                .createOrReplace();
        log.info("Created/updated Deployment {} for function {}", deployment.getMetadata().getName(), spec.name());

        client.services()
                .inNamespace(resolvedNamespace)
                .resource(service)
                .createOrReplace();
        log.info("Created/updated Service {} for function {}", service.getMetadata().getName(), spec.name());

        if (spec.scalingConfig() != null && spec.scalingConfig().strategy() == ScalingStrategy.HPA) {
            HorizontalPodAutoscaler hpa = builder.buildHpa(spec);
            if (hpa != null) {
                client.autoscaling().v2().horizontalPodAutoscalers()
                        .inNamespace(resolvedNamespace)
                        .resource(hpa)
                        .createOrReplace();
                log.info("Created/updated HPA {} for function {}", hpa.getMetadata().getName(), spec.name());
            }
        }

        String serviceUrl = String.format("http://%s.%s.svc.cluster.local:8080/invoke",
                KubernetesDeploymentBuilder.serviceName(spec.name()), resolvedNamespace);
        log.info("Function {} provisioned at {}", spec.name(), serviceUrl);
        return serviceUrl;
    }

    /**
     * Deletes Deployment, Service, and HPA (if exists) for a function.
     */
    public void deprovision(String functionName) {
        String name = KubernetesDeploymentBuilder.deploymentName(functionName);
        KubernetesClient client = clientProvider.getObject();

        client.autoscaling().v2().horizontalPodAutoscalers()
                .inNamespace(resolvedNamespace)
                .withName(name)
                .delete();

        client.services()
                .inNamespace(resolvedNamespace)
                .withName(KubernetesDeploymentBuilder.serviceName(functionName))
                .delete();

        client.apps().deployments()
                .inNamespace(resolvedNamespace)
                .withName(name)
                .delete();

        log.info("Deprovisioned resources for function {}", functionName);
    }

    /**
     * Patches the replica count of a function's Deployment.
     * Used by the internal scaler.
     */
    public void setReplicas(String functionName, int replicas) {
        String name = KubernetesDeploymentBuilder.deploymentName(functionName);
        KubernetesClient client = clientProvider.getObject();
        client.apps().deployments()
                .inNamespace(resolvedNamespace)
                .withName(name)
                .scale(replicas);
        log.debug("Scaled function {} to {} replicas", functionName, replicas);
    }

    /**
     * Returns the number of ready replicas for a function's Deployment.
     */
    public int getReadyReplicas(String functionName) {
        String name = KubernetesDeploymentBuilder.deploymentName(functionName);
        KubernetesClient client = clientProvider.getObject();
        Deployment deployment = client.apps().deployments()
                .inNamespace(resolvedNamespace)
                .withName(name)
                .get();
        if (deployment == null || deployment.getStatus() == null || deployment.getStatus().getReadyReplicas() == null) {
            return 0;
        }
        return deployment.getStatus().getReadyReplicas();
    }

    public String getResolvedNamespace() {
        return resolvedNamespace;
    }

    private static String resolveNamespace(KubernetesProperties properties) {
        if (properties.namespace() != null && !properties.namespace().isBlank()) {
            return properties.namespace();
        }
        String env = System.getenv("POD_NAMESPACE");
        return env == null || env.isBlank() ? "default" : env;
    }
}
