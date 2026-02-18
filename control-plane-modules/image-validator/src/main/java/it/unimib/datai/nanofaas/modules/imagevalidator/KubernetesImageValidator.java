package it.unimib.datai.nanofaas.modules.imagevalidator;

import io.fabric8.kubernetes.api.model.ContainerState;
import io.fabric8.kubernetes.api.model.ContainerStateWaiting;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.config.KubernetesProperties;
import it.unimib.datai.nanofaas.controlplane.registry.ImageValidationException;
import it.unimib.datai.nanofaas.controlplane.registry.ImageValidator;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class KubernetesImageValidator implements ImageValidator {
    private static final String NATIVE_IMAGE_CODE_PROPERTY = "org.graalvm.nativeimage.imagecode";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(500);

    private final ObjectProvider<KubernetesClient> clientProvider;
    private final KubernetesProperties properties;
    private final Duration timeout;
    private final Duration pollInterval;

    public KubernetesImageValidator(ObjectProvider<KubernetesClient> clientProvider, KubernetesProperties properties) {
        this(clientProvider, properties, DEFAULT_TIMEOUT, DEFAULT_POLL_INTERVAL);
    }

    KubernetesImageValidator(ObjectProvider<KubernetesClient> clientProvider,
                             KubernetesProperties properties,
                             Duration timeout,
                             Duration pollInterval) {
        this.clientProvider = clientProvider;
        this.properties = properties;
        this.timeout = timeout;
        this.pollInterval = pollInterval;
    }

    @Override
    public void validate(FunctionSpec spec) {
        if (spec.executionMode() != ExecutionMode.DEPLOYMENT) {
            return;
        }
        // Fabric8's generic Kubernetes model serialization relies heavily on runtime reflection.
        // In GraalVM native runtime this may fail despite targeted hints, so skip proactive
        // image validation and let deployment-time pull errors surface normally.
        if (isNativeRuntime()) {
            return;
        }

        KubernetesClient client;
        try {
            client = clientProvider.getObject();
        } catch (Exception e) {
            throw ImageValidationException.registryUnavailable(spec.image(), "Kubernetes client unavailable");
        }

        String namespace = resolveNamespace(client);
        String podName = validationPodName(spec.name());
        Pod pod = buildValidationPod(spec, podName);

        try {
            client.pods().inNamespace(namespace).resource(pod).create();
            waitForImagePullResult(client, namespace, podName, spec.image());
        } finally {
            try {
                client.pods().inNamespace(namespace).withName(podName).delete();
            } catch (Exception ignored) {
                // Best-effort cleanup.
            }
        }
    }

    private void waitForImagePullResult(KubernetesClient client, String namespace, String podName, String image) {
        long deadlineNs = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadlineNs) {
            Pod pod = client.pods().inNamespace(namespace).withName(podName).get();
            if (pod != null && pod.getStatus() != null) {
                if (isImagePulled(pod)) {
                    return;
                }

                ImageValidationException pullError = extractPullError(pod, image);
                if (pullError != null) {
                    throw pullError;
                }
            }

            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw ImageValidationException.registryUnavailable(image, "Interrupted while validating image");
            }
        }

        throw ImageValidationException.registryUnavailable(image, "Timed out waiting for image pull");
    }

    private boolean isImagePulled(Pod pod) {
        String phase = pod.getStatus().getPhase();
        if ("Running".equalsIgnoreCase(phase) || "Succeeded".equalsIgnoreCase(phase)) {
            return true;
        }
        if (pod.getStatus().getContainerStatuses() == null) {
            return false;
        }
        return pod.getStatus().getContainerStatuses().stream()
                .map(cs -> cs.getState())
                .filter(state -> state != null)
                .anyMatch(state -> state.getRunning() != null || state.getTerminated() != null);
    }

    private ImageValidationException extractPullError(Pod pod, String image) {
        if (pod.getStatus().getContainerStatuses() == null) {
            return null;
        }
        for (var status : pod.getStatus().getContainerStatuses()) {
            ContainerState state = status.getState();
            if (state == null || state.getWaiting() == null) {
                continue;
            }
            ContainerStateWaiting waiting = state.getWaiting();
            String reason = waiting.getReason() == null ? "" : waiting.getReason();
            String message = waiting.getMessage() == null ? "" : waiting.getMessage();
            String lowerReason = reason.toLowerCase(Locale.ROOT);
            String lowerMessage = message.toLowerCase(Locale.ROOT);

            if (lowerMessage.contains("not found")
                    || lowerMessage.contains("manifest unknown")
                    || lowerMessage.contains("name unknown")
                    || "invalidimagename".equals(lowerReason)) {
                return ImageValidationException.notFound(image);
            }

            if (lowerMessage.contains("pull access denied")
                    || lowerMessage.contains("authentication required")
                    || lowerMessage.contains("unauthorized")
                    || lowerMessage.contains("denied")) {
                return ImageValidationException.authRequired(image);
            }

            if ("errimagepull".equals(lowerReason) || "imagepullbackoff".equals(lowerReason)) {
                return ImageValidationException.registryUnavailable(image, reason + ": " + message);
            }
        }
        return null;
    }

    private Pod buildValidationPod(FunctionSpec spec, String podName) {
        List<LocalObjectReference> imagePullSecrets = new ArrayList<>();
        if (spec.imagePullSecrets() != null) {
            for (String secret : spec.imagePullSecrets()) {
                if (secret != null && !secret.isBlank()) {
                    imagePullSecrets.add(new LocalObjectReference(secret.trim()));
                }
            }
        }

        return new PodBuilder()
                .withNewMetadata()
                    .withName(podName)
                    .addToLabels("app", "nanofaas")
                    .addToLabels("nanofaas.io/purpose", "image-validation")
                    .addToLabels("nanofaas.io/function", spec.name())
                .endMetadata()
                .withNewSpec()
                    .addNewContainer()
                        .withName("validate")
                        .withImage(spec.image())
                        .withImagePullPolicy("Always")
                    .endContainer()
                    .withImagePullSecrets(imagePullSecrets)
                    .withRestartPolicy("Never")
                    .withTerminationGracePeriodSeconds(0L)
                .endSpec()
                .build();
    }

    private String resolveNamespace(KubernetesClient client) {
        if (properties.namespace() != null && !properties.namespace().isBlank()) {
            return properties.namespace();
        }
        if (client.getNamespace() != null && !client.getNamespace().isBlank()) {
            return client.getNamespace();
        }
        return "default";
    }

    private static String validationPodName(String functionName) {
        String normalized = functionName == null ? "fn" : functionName.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-]", "-")
                .replaceAll("-{2,}", "-");
        normalized = normalized.replaceAll("^-+", "").replaceAll("-+$", "");
        if (normalized.isBlank()) {
            normalized = "fn";
        }
        String suffix = Long.toString(System.nanoTime(), 36);
        String base = "imgval-" + normalized;
        int maxBase = 63 - 1 - suffix.length();
        if (base.length() > maxBase) {
            base = base.substring(0, maxBase);
            base = base.replaceAll("-+$", "");
        }
        return base + "-" + suffix;
    }

    private static boolean isNativeRuntime() {
        return System.getProperty(NATIVE_IMAGE_CODE_PROPERTY) != null;
    }
}
