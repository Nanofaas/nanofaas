package it.unimib.datai.nanofaas.modules.k8s.deployment;

import io.fabric8.kubernetes.client.KubernetesClient;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.containerdeployment.ManagedReplicaPoolFactory;
import it.unimib.datai.nanofaas.containerdeployment.ManagedReplicaSet;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentProvider;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedTargetResolution;
import it.unimib.datai.nanofaas.controlplane.deployment.ProvisionResult;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatus;
import it.unimib.datai.nanofaas.modules.k8s.dispatch.KubernetesDeploymentBuilder;
import it.unimib.datai.nanofaas.modules.k8s.dispatch.KubernetesResourceManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Kubernetes backend. The control plane picks the pod of every invocation itself, from the ready
 * endpoints of the function's headless Service, through the same replica pool the local container
 * providers use: kube-proxy balanced per connection, so pods added by a scale-up could stay idle
 * behind the connections the control plane kept open.
 */
@Component
public class KubernetesManagedDeploymentProvider implements ManagedDeploymentProvider, AutoCloseable {

    public static final String BACKEND_ID = "k8s";
    private static final int DEFAULT_CONCURRENCY = 4;
    private static final long DEFAULT_TIMEOUT_MS = 30_000;

    private final KubernetesResourceManager resourceManager;
    private final ObjectProvider<KubernetesClient> clients;
    private final ManagedReplicaPoolFactory pools = new ManagedReplicaPoolFactory();
    /** Keyed by Service name, which is what an EndpointSlice names. */
    private final ConcurrentMap<String, FunctionPods> functions = new ConcurrentHashMap<>();
    private volatile EndpointSliceWatcher watcher;

    private static final class FunctionPods {
        final ManagedReplicaSet pool;
        volatile FunctionSpec spec;
        volatile int backends;

        FunctionPods(ManagedReplicaSet pool, FunctionSpec spec) {
            this.pool = pool;
            this.spec = spec;
        }
    }

    public KubernetesManagedDeploymentProvider(KubernetesResourceManager resourceManager,
                                               ObjectProvider<KubernetesClient> clients) {
        this.resourceManager = resourceManager;
        this.clients = clients;
    }

    @Override
    public String backendId() {
        return BACKEND_ID;
    }

    @Override
    public boolean isAvailable() {
        // Bean creation already proves the Fabric8 client boundary is wired for this module.
        return true;
    }

    @Override
    public boolean supports(FunctionSpec spec) {
        return spec.executionMode() == ExecutionMode.DEPLOYMENT;
    }

    @Override
    public ManagedTargetResolution acquireInvocationTarget(String functionName, Map<String, String> deploymentObjects,
                                                           String executionId, String dispatchAttempt) {
        // No lifecycle lock: the pool's own monitor fences what matters, and dispatch must not wait
        // for provisioning or scaling of this or any other function.
        var pods = functions.get(KubernetesDeploymentBuilder.serviceName(functionName));
        if (pods == null) return ManagedTargetResolution.UNAVAILABLE;
        var target = pods.pool.acquire(executionId, dispatchAttempt);
        return target == null ? ManagedTargetResolution.UNAVAILABLE : ManagedTargetResolution.reserved(target);
    }

    @Override
    public ProvisionResult provision(FunctionSpec spec) {
        // Watcher first: one that cannot start (no endpointslices permission) must fail the
        // registration before anything is created, not leave its Deployment and Service behind.
        startWatching();
        resourceManager.provision(spec);
        follow(spec);
        return new ProvisionResult(null, backendId(), Map.of(
                ProvisionResult.DEPLOYMENT, KubernetesDeploymentBuilder.deploymentName(spec.name()),
                ProvisionResult.SERVICE, KubernetesDeploymentBuilder.serviceName(spec.name()),
                ProvisionResult.NAMESPACE, resourceManager.getResolvedNamespace()));
    }

    @Override
    public ProvisionResult reconcile(FunctionSpec spec,
                                     int desiredReplicas,
                                     Map<String, String> deploymentObjects) {
        startWatching();
        ProvisionResult result = resourceManager.reconcile(spec, desiredReplicas, deploymentObjects);
        follow(spec);
        return result;
    }

    @Override
    public void updateSpec(FunctionSpec spec) {
        String service = KubernetesDeploymentBuilder.serviceName(spec.name());
        var pods = functions.get(service);
        if (pods == null) return;
        pods.spec = spec;
        refresh(service);
    }

    @Override
    public void deprovision(String functionName) {
        // Resources first: when a delete fails the function is restored without a reconcile, and its
        // pods are still serving, so its pool must still be there. The generation fence already
        // stops new dispatch while the removal runs.
        resourceManager.deprovision(functionName);
        var pods = functions.remove(KubernetesDeploymentBuilder.serviceName(functionName));
        if (pods != null) pods.pool.close();
    }

    @Override
    public void setReplicas(String functionName, int replicas) {
        resourceManager.setReplicas(functionName, replicas);
    }

    @Override
    public int getReadyReplicas(String functionName) {
        return getReplicaStatus(functionName).readyReplicas();
    }

    /**
     * Desired is the Deployment's; ready is the pods the dispatch can reach. The Deployment can report
     * a pod ready before its endpoint reaches the pool, and the wake-up gate releases the first
     * dispatch on ready: counting the Deployment's would send it into an empty pool.
     */
    @Override
    public ReplicaStatus getReplicaStatus(String functionName) {
        int desired = resourceManager.getReplicaStatus(functionName).desiredReplicas();
        var pods = functions.get(KubernetesDeploymentBuilder.serviceName(functionName));
        return new ReplicaStatus(desired, pods == null ? 0 : Math.min(desired, pods.backends));
    }

    @Override
    public void close() {
        var current = watcher;
        if (current != null) current.close();
        functions.values().forEach(pods -> pods.pool.close());
        functions.clear();
        pools.close();
    }

    private void follow(FunctionSpec spec) {
        String service = KubernetesDeploymentBuilder.serviceName(spec.name());
        functions.compute(service, (name, existing) -> {
            if (existing == null) return new FunctionPods(pools.create(spec.name()), spec);
            existing.spec = spec;
            return existing;
        });
        // Slices listed before the function existed produced no event for it: read them now.
        refresh(service);
    }

    private void startWatching() {
        if (watcher != null) return;
        synchronized (this) {
            if (watcher != null) return;
            var started = new EndpointSliceWatcher(clients.getObject(), resourceManager.getResolvedNamespace(), this::refresh);
            started.start();
            watcher = started;
        }
    }

    /**
     * Applies a Service's current ready pods. It reads the watcher's store when it applies, under the
     * function's lock, never an event's payload: whatever order events are handled in, the last
     * refresh applies the latest state.
     */
    private void refresh(String service) {
        var pods = functions.get(service);
        var current = watcher;
        if (pods == null || current == null) return;
        synchronized (pods) {
            if (functions.get(service) != pods) return;   // removed meanwhile
            List<String> backends = current.readyBackends(service);
            int perPod = perPodConcurrency(pods.spec);
            pods.pool.updateBackends(backends);
            pods.pool.updateLimits(Math.max(1, backends.size() * perPod), perPod, timeout(pods.spec));
            pods.backends = backends.size();
        }
    }

    private static int perPodConcurrency(FunctionSpec spec) {
        return spec.concurrency() == null ? DEFAULT_CONCURRENCY : Math.max(1, spec.concurrency());
    }

    private static Duration timeout(FunctionSpec spec) {
        return Duration.ofMillis(spec.timeoutMs() == null ? DEFAULT_TIMEOUT_MS : spec.timeoutMs());
    }
}
