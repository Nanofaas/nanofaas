package it.unimib.datai.nanofaas.controlplane.dispatch;

import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.config.KubernetesProperties;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class KubernetesDispatcher implements Dispatcher {
    private static final Logger log = LoggerFactory.getLogger(KubernetesDispatcher.class);

    private final KubernetesClient client;
    private final KubernetesProperties properties;
    private final ExecutorService executor;
    private final String resolvedNamespace;

    public KubernetesDispatcher(KubernetesClient client,
                                KubernetesProperties properties,
                                @Qualifier("k8sDispatcherExecutor") ExecutorService executor) {
        this.client = client;
        this.properties = properties;
        this.executor = executor;
        this.resolvedNamespace = resolveNamespace(properties);
    }

    @Override
    public CompletableFuture<InvocationResult> dispatch(InvocationTask task) {
        int timeoutSeconds = properties.apiTimeoutSecondsOrDefault();

        return CompletableFuture.supplyAsync(() -> createJob(task), executor)
                .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .exceptionally(ex -> handleError(ex, task, timeoutSeconds));
    }

    private InvocationResult createJob(InvocationTask task) {
        try {
            KubernetesJobBuilder builder = new KubernetesJobBuilder(properties);
            Job job = builder.build(task);

            Job created = client.batch().v1().jobs()
                    .inNamespace(resolvedNamespace)
                    .resource(job)
                    .create();

            log.debug("Created K8s Job {} for execution {}",
                    created.getMetadata().getName(), task.executionId());

            return InvocationResult.success(null);
        } catch (KubernetesClientException ex) {
            log.error("K8s API error creating job for execution {}: {}",
                    task.executionId(), ex.getMessage());
            throw ex;
        }
    }

    private InvocationResult handleError(Throwable ex, InvocationTask task, int timeoutSeconds) {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;

        if (cause instanceof TimeoutException) {
            log.error("K8s API timeout ({} seconds) creating job for execution {}",
                    timeoutSeconds, task.executionId());
            return InvocationResult.error("K8S_TIMEOUT",
                    "Kubernetes API timeout after " + timeoutSeconds + " seconds");
        }

        if (cause instanceof KubernetesClientException kce) {
            return InvocationResult.error("K8S_ERROR", kce.getMessage());
        }

        log.error("Unexpected error creating K8s job for execution {}: {}",
                task.executionId(), cause.getMessage());
        return InvocationResult.error("DISPATCH_ERROR", cause.getMessage());
    }

    private static String resolveNamespace(KubernetesProperties properties) {
        if (properties.namespace() != null && !properties.namespace().isBlank()) {
            return properties.namespace();
        }
        String env = System.getenv("POD_NAMESPACE");
        return env == null || env.isBlank() ? "default" : env;
    }
}
