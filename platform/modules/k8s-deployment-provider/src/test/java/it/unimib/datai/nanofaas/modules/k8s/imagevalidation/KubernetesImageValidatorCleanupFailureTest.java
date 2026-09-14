package it.unimib.datai.nanofaas.modules.k8s.imagevalidation;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.modules.k8s.config.KubernetesProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class KubernetesImageValidatorCleanupFailureTest {

    @Test
    void cleanupFailureIsReportedWithoutMaskingTheValidationFailure(CapturedOutput output) {
        KubernetesClient client = mock(KubernetesClient.class);
        @SuppressWarnings("unchecked")
        MixedOperation<Pod, PodList, PodResource> pods = mock(MixedOperation.class);
        @SuppressWarnings("unchecked")
        NonNamespaceOperation<Pod, PodList, PodResource> namespacedPods = mock(NonNamespaceOperation.class);
        PodResource createResource = mock(PodResource.class);
        PodResource deleteResource = mock(PodResource.class);

        when(client.getNamespace()).thenReturn("test");
        when(client.pods()).thenReturn(pods);
        when(pods.inNamespace("test")).thenReturn(namespacedPods);
        when(namespacedPods.resource(any(Pod.class))).thenReturn(createResource);
        when(namespacedPods.withName(anyString())).thenReturn(deleteResource);
        when(createResource.create()).thenThrow(new IllegalStateException("create failed"));
        when(deleteResource.delete()).thenThrow(new IllegalStateException("delete failed"));

        @SuppressWarnings("unchecked")
        ObjectProvider<KubernetesClient> clientProvider = mock(ObjectProvider.class);
        when(clientProvider.getObject()).thenReturn(client);
        KubernetesImageValidator validator = new KubernetesImageValidator(
                clientProvider,
                new KubernetesProperties("test", null),
                Duration.ofSeconds(1),
                Duration.ofMillis(1));

        assertThatThrownBy(() -> validator.validate(deploymentSpec()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("create failed");
        assertThat(output)
                .contains("Failed to delete image validation pod test/")
                .contains("delete failed");
    }

    private static FunctionSpec deploymentSpec() {
        return new FunctionSpec(
                "cleanup-test", "example.invalid/function:latest", null, null, null,
                1_000, 1, 1, 0, null, ExecutionMode.DEPLOYMENT, null, null, null);
    }
}
