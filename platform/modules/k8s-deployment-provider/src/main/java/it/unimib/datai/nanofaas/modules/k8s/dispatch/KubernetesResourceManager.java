package it.unimib.datai.nanofaas.modules.k8s.dispatch;

import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.fabric8.kubernetes.client.utils.Serialization;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.deployment.ProvisionResult;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatus;
import it.unimib.datai.nanofaas.modules.k8s.config.KubernetesProperties;
import it.unimib.datai.nanofaas.modules.k8s.deployment.KubernetesManagedDeploymentProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

@Component
public class KubernetesResourceManager {
    private static final Logger log = LoggerFactory.getLogger(KubernetesResourceManager.class);

    private final ObjectProvider<KubernetesClient> clientProvider;
    private final KubernetesDeploymentBuilder builder;
    private final String resolvedNamespace;

    public KubernetesResourceManager(ObjectProvider<KubernetesClient> clientProvider, KubernetesProperties properties) {
        this.clientProvider = clientProvider;
        this.builder = new KubernetesDeploymentBuilder(properties);
        this.resolvedNamespace = resolveNamespace(properties);
    }

    /**
     * Creates Deployment + Service (+ HPA if strategy=HPA) for a function.
     * Reconciles resources without deleting stable objects first, preserving
     * service identity and deployment history across updates.
     * Returns the service URL for invocations.
     */
    public String provision(FunctionSpec spec) {
        Deployment deployment = builder.buildDeployment(spec);
        Service service = builder.buildService(spec);
        KubernetesClient client = clientProvider.getObject();
        boolean deploymentCreated = false;
        boolean serviceCreated = false;
        boolean hpaCreated = false;

        try {
            deploymentCreated = createOrPatchDeployment(client, deployment);
            log.info("Created/updated Deployment {} for function {}", deployment.getMetadata().getName(), spec.name());

            serviceCreated = createOrPatchService(client, service);
            log.info("Created/updated Service {} for function {}", service.getMetadata().getName(), spec.name());

            if (isHpaManaged(spec)) {
                HorizontalPodAutoscaler hpa = builder.buildHpa(spec);
                if (hpa != null) {
                    hpaCreated = createOrPatchHpa(client, hpa);
                    log.info("Created/updated HPA {} for function {}", hpa.getMetadata().getName(), spec.name());
                }
            } else {
                client.autoscaling().v2().horizontalPodAutoscalers()
                        .inNamespace(resolvedNamespace)
                        .withName(KubernetesDeploymentBuilder.deploymentName(spec.name()))
                        .delete();
            }
        } catch (RuntimeException failure) {
            String deploymentName = KubernetesDeploymentBuilder.deploymentName(spec.name());
            rollbackOnFailure(client, deploymentName, KubernetesDeploymentBuilder.serviceName(spec.name()),
                    deploymentCreated, serviceCreated, hpaCreated, failure);
            throw failure;
        }

        String serviceUrl = serviceUrl(KubernetesDeploymentBuilder.serviceName(spec.name()), resolvedNamespace);
        log.info("Function {} provisioned at {}", spec.name(), serviceUrl);
        return serviceUrl;
    }

    /**
     * Restores a function's resources at their persisted names without destroying
     * anything healthy: existing Deployment and Service are left alone, only the
     * missing pieces are created, and a non-HPA Deployment is scaled to the
     * coordinator-driven replica target. HPA config is restored but its replica
     * count is left to Kubernetes.
     */
    public ProvisionResult reconcile(FunctionSpec spec,
                                     int desiredReplicas,
                                     Map<String, String> deploymentObjects) {
        ResourceNames names = requirePersistedNames(spec, deploymentObjects);
        KubernetesClient client = clientProvider.getObject();

        Deployment existingDeployment = client.apps().deployments()
                .inNamespace(names.namespace()).withName(names.deployment()).get();
        Service existingService = client.services()
                .inNamespace(names.namespace()).withName(names.service()).get();

        if (existingDeployment == null) {
            createDeployment(client, spec, names, desiredReplicas);
        } else if (!isHpaManaged(spec)
                && !Objects.equals(existingDeployment.getSpec().getReplicas(), desiredReplicas)) {
            client.apps().deployments().inNamespace(names.namespace())
                    .withName(names.deployment()).scale(desiredReplicas);
        }
        if (existingService == null) {
            createService(client, spec, names);
        }
        if (isHpaManaged(spec)) {
            if (getHpa(client, names) == null) {
                createHpa(client, spec, names);
            }
        } else if (getHpa(client, names) != null) {
            client.autoscaling().v2().horizontalPodAutoscalers()
                    .inNamespace(names.namespace()).withName(names.deployment()).delete();
        }

        return provisionResult(names);
    }

    private ResourceNames requirePersistedNames(FunctionSpec spec, Map<String, String> deploymentObjects) {
        String namespace = deploymentObjects.get(ProvisionResult.NAMESPACE);
        String deployment = deploymentObjects.get(ProvisionResult.DEPLOYMENT);
        String service = deploymentObjects.get(ProvisionResult.SERVICE);
        if (namespace == null || namespace.isBlank()
                || deployment == null || deployment.isBlank()
                || service == null || service.isBlank()) {
            throw new IllegalArgumentException("Persisted deployment objects are missing required names");
        }
        String expectedDeployment = KubernetesDeploymentBuilder.deploymentName(spec.name());
        String expectedService = KubernetesDeploymentBuilder.serviceName(spec.name());
        if (!namespace.equals(resolvedNamespace)
                || !deployment.equals(expectedDeployment)
                || !service.equals(expectedService)) {
            throw new IllegalArgumentException(
                    "Persisted deployment object names do not match function '" + spec.name() + "'");
        }
        return new ResourceNames(namespace, deployment, service);
    }

    private void createDeployment(KubernetesClient client, FunctionSpec spec, ResourceNames names, int desiredReplicas) {
        Deployment deployment = builder.buildDeployment(spec);
        if (!isHpaManaged(spec)) {
            deployment.getSpec().setReplicas(desiredReplicas);
        }
        client.apps().deployments().inNamespace(names.namespace()).resource(deployment).create();
    }

    private void createService(KubernetesClient client, FunctionSpec spec, ResourceNames names) {
        Service service = builder.buildService(spec);
        client.services().inNamespace(names.namespace()).resource(service).create();
    }

    private HorizontalPodAutoscaler getHpa(KubernetesClient client, ResourceNames names) {
        return client.autoscaling().v2().horizontalPodAutoscalers()
                .inNamespace(names.namespace()).withName(names.deployment()).get();
    }

    private void createHpa(KubernetesClient client, FunctionSpec spec, ResourceNames names) {
        HorizontalPodAutoscaler hpa = builder.buildHpa(spec);
        if (hpa != null) {
            client.autoscaling().v2().horizontalPodAutoscalers()
                    .inNamespace(names.namespace()).resource(hpa).create();
        }
    }

    private static boolean isHpaManaged(FunctionSpec spec) {
        return KubernetesDeploymentBuilder.hpaOwnsScaling(spec.scalingConfig());
    }

    private static String serviceUrl(String serviceName, String namespace) {
        return String.format("http://%s.%s.svc.cluster.local:8080/invoke", serviceName, namespace);
    }

    private ProvisionResult provisionResult(ResourceNames names) {
        return new ProvisionResult(serviceUrl(names.service(), names.namespace()),
                KubernetesManagedDeploymentProvider.BACKEND_ID, Map.of(
                ProvisionResult.DEPLOYMENT, names.deployment(),
                ProvisionResult.SERVICE, names.service(),
                ProvisionResult.NAMESPACE, names.namespace()));
    }

    private record ResourceNames(String namespace, String deployment, String service) {}

    private boolean createOrPatchDeployment(KubernetesClient client, Deployment deployment) {
        var deploymentResource = client.apps().deployments()
                .inNamespace(resolvedNamespace)
                .withName(deployment.getMetadata().getName());
        if (deploymentResource.get() == null) {
            client.apps().deployments()
                    .inNamespace(resolvedNamespace)
                    .resource(deployment)
                    .create();
            return true;
        }
        deploymentResource.patch(PatchContext.of(PatchType.JSON_MERGE), Serialization.asJson(deployment));
        return false;
    }

    private boolean createOrPatchService(KubernetesClient client, Service service) {
        var serviceResource = client.services()
                .inNamespace(resolvedNamespace)
                .withName(service.getMetadata().getName());
        if (serviceResource.get() == null) {
            client.services()
                    .inNamespace(resolvedNamespace)
                    .resource(service)
                    .create();
            return true;
        }
        serviceResource.patch(PatchContext.of(PatchType.JSON_MERGE), Serialization.asJson(service));
        return false;
    }

    private boolean createOrPatchHpa(KubernetesClient client, HorizontalPodAutoscaler hpa) {
        var hpaResource = client.autoscaling().v2().horizontalPodAutoscalers()
                .inNamespace(resolvedNamespace)
                .withName(hpa.getMetadata().getName());
        if (hpaResource.get() == null) {
            client.autoscaling().v2().horizontalPodAutoscalers()
                    .inNamespace(resolvedNamespace)
                    .resource(hpa)
                    .create();
            return true;
        }
        hpaResource.patch(PatchContext.of(PatchType.JSON_MERGE), Serialization.asJson(hpa));
        return false;
    }

    private void rollbackOnFailure(KubernetesClient client, String deploymentName, String serviceName,
                                   boolean deploymentCreated, boolean serviceCreated, boolean hpaCreated,
                                   RuntimeException failure) {
        if (hpaCreated) {
            suppressCleanupFailure(failure, () -> client.autoscaling().v2().horizontalPodAutoscalers()
                    .inNamespace(resolvedNamespace).withName(deploymentName).delete());
        }
        if (serviceCreated) {
            suppressCleanupFailure(failure, () -> client.services().inNamespace(resolvedNamespace)
                    .withName(serviceName).delete());
        }
        if (deploymentCreated) {
            suppressCleanupFailure(failure, () -> client.apps().deployments().inNamespace(resolvedNamespace)
                    .withName(deploymentName).delete());
        }
    }

    private static void suppressCleanupFailure(RuntimeException provisioningFailure, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException cleanupFailure) {
            provisioningFailure.addSuppressed(cleanupFailure);
        }
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
        return getReplicaStatus(functionName).readyReplicas();
    }

    public ReplicaStatus getReplicaStatus(String functionName) {
        String name = KubernetesDeploymentBuilder.deploymentName(functionName);
        KubernetesClient client = clientProvider.getObject();
        Deployment deployment = client.apps().deployments()
                .inNamespace(resolvedNamespace)
                .withName(name)
                .get();
        if (deployment == null) {
            return new ReplicaStatus(0, 0);
        }
        Integer desired = deployment.getSpec() == null ? null : deployment.getSpec().getReplicas();
        Integer ready = deployment.getStatus() == null ? null : deployment.getStatus().getReadyReplicas();
        return new ReplicaStatus(desired == null ? 0 : desired, ready == null ? 0 : ready);
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
