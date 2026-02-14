package it.unimib.datai.nanofaas.controlplane.registry;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.config.KubernetesProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class KubernetesImageValidatorTest {

    @Test
    void validate_deployment_usesCreateInsteadOfCreateOrReplace() {
        @SuppressWarnings("unchecked")
        ObjectProvider<KubernetesClient> provider = mock(ObjectProvider.class);
        KubernetesClient client = mock(KubernetesClient.class);
        @SuppressWarnings("unchecked")
        MixedOperation<Pod, PodList, PodResource> pods = mock(MixedOperation.class);
        PodResource podResource = mock(PodResource.class);
        PodResource namedPod = mock(PodResource.class);

        when(provider.getObject()).thenReturn(client);
        when(client.pods()).thenReturn(pods);
        when(pods.inNamespace(anyString())).thenReturn(pods);
        when(pods.resource(any(Pod.class))).thenReturn(podResource);
        when(pods.withName(anyString())).thenReturn(namedPod);
        when(namedPod.get()).thenReturn(new PodBuilder().withNewStatus().withPhase("Running").endStatus().build());

        KubernetesImageValidator validator = new KubernetesImageValidator(
                provider,
                new KubernetesProperties("nanofaas", null)
        );

        validator.validate(new FunctionSpec(
                "word-stats-java",
                "ghcr.io/miciav/nanofaas/java-word-stats:v0.11.4-arm64",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                ExecutionMode.DEPLOYMENT,
                null,
                null,
                null
        ));

        verify(podResource).create();
        verify(podResource, never()).createOrReplace();
    }

    @Test
    void validate_deployment_skipsValidationInNativeRuntime() {
        @SuppressWarnings("unchecked")
        ObjectProvider<KubernetesClient> provider = mock(ObjectProvider.class);
        KubernetesClient client = mock(KubernetesClient.class);
        when(provider.getObject()).thenReturn(client);

        KubernetesImageValidator validator = new KubernetesImageValidator(
                provider,
                new KubernetesProperties("nanofaas", null)
        );

        String previous = System.getProperty("org.graalvm.nativeimage.imagecode");
        System.setProperty("org.graalvm.nativeimage.imagecode", "runtime");
        try {
            validator.validate(new FunctionSpec(
                    "word-stats-java",
                    "ghcr.io/miciav/nanofaas/java-word-stats:v0.11.4-arm64",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    ExecutionMode.DEPLOYMENT,
                    null,
                    null,
                    null
            ));
        } finally {
            if (previous == null) {
                System.clearProperty("org.graalvm.nativeimage.imagecode");
            } else {
                System.setProperty("org.graalvm.nativeimage.imagecode", previous);
            }
        }

        verifyNoInteractions(client);
    }
}
