package it.unimib.datai.nanofaas.controlplane.deployment;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.dispatch.KubernetesResourceManager;
import org.springframework.stereotype.Component;

@Component
public class KubernetesManagedDeploymentProvider implements ManagedDeploymentProvider {

    public static final String BACKEND_ID = "k8s";

    private final KubernetesResourceManager resourceManager;

    public KubernetesManagedDeploymentProvider(KubernetesResourceManager resourceManager) {
        this.resourceManager = resourceManager;
    }

    @Override
    public String backendId() {
        return BACKEND_ID;
    }

    @Override
    public boolean isAvailable() {
        return resourceManager != null;
    }

    @Override
    public boolean supports(FunctionSpec spec) {
        return spec.executionMode() == ExecutionMode.DEPLOYMENT;
    }

    @Override
    public ProvisionResult provision(FunctionSpec spec) {
        return new ProvisionResult(resourceManager.provision(spec), backendId());
    }

    @Override
    public void deprovision(String functionName) {
        resourceManager.deprovision(functionName);
    }

    @Override
    public void setReplicas(String functionName, int replicas) {
        resourceManager.setReplicas(functionName, replicas);
    }

    @Override
    public int getReadyReplicas(String functionName) {
        return resourceManager.getReadyReplicas(functionName);
    }
}
