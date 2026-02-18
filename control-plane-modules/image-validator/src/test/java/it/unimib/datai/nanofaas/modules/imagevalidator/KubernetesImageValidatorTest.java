package it.unimib.datai.nanofaas.modules.imagevalidator;

import io.fabric8.kubernetes.api.model.ContainerStateBuilder;
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.config.KubernetesProperties;
import it.unimib.datai.nanofaas.controlplane.registry.ImageValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KubernetesImageValidatorTest {

    @Test
    void validate_deployment_usesCreateInsteadOfCreateOrReplace() {
        K8sMocks mocks = mockK8s(runningPod(), null);

        KubernetesImageValidator validator = new KubernetesImageValidator(
                mocks.provider,
                new KubernetesProperties("nanofaas", null)
        );

        validator.validate(deploymentSpec("word-stats-java", "ghcr.io/miciav/nanofaas/java-word-stats:v0.11.4-arm64", null));

        verify(mocks.podResource).create();
        verify(mocks.podResource, never()).createOrReplace();
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

    @Test
    void validate_nonDeployment_skipsValidation() {
        @SuppressWarnings("unchecked")
        ObjectProvider<KubernetesClient> provider = mock(ObjectProvider.class);

        KubernetesImageValidator validator = new KubernetesImageValidator(
                provider,
                new KubernetesProperties("nanofaas", null)
        );

        FunctionSpec localSpec = new FunctionSpec(
                "echo",
                "ghcr.io/example/echo:v1",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                ExecutionMode.LOCAL,
                null,
                null,
                null
        );

        validator.validate(localSpec);

        verifyNoInteractions(provider);
    }

    @Test
    void validate_whenClientProviderFails_throwsRegistryUnavailable() {
        @SuppressWarnings("unchecked")
        ObjectProvider<KubernetesClient> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenThrow(new RuntimeException("k8s unavailable"));

        KubernetesImageValidator validator = new KubernetesImageValidator(
                provider,
                new KubernetesProperties("nanofaas", null)
        );

        assertThatThrownBy(() -> validator.validate(deploymentSpec("fn", "ghcr.io/example/fn:v1", null)))
                .isInstanceOf(ImageValidationException.class)
                .extracting(ex -> ((ImageValidationException) ex).errorCode())
                .isEqualTo("IMAGE_REGISTRY_UNAVAILABLE");
    }

    @Test
    void validate_whenImageNotFound_mapsToImageNotFoundError() {
        Pod pullError = waitingPod("ErrImagePull", "manifest unknown");
        K8sMocks mocks = mockK8s(pullError, null);

        KubernetesImageValidator validator = new KubernetesImageValidator(
                mocks.provider,
                new KubernetesProperties("nanofaas", null)
        );

        assertThatThrownBy(() -> validator.validate(deploymentSpec("fn", "ghcr.io/example/missing:v1", null)))
                .isInstanceOf(ImageValidationException.class)
                .extracting(ex -> ((ImageValidationException) ex).errorCode())
                .isEqualTo("IMAGE_NOT_FOUND");
    }

    @Test
    void validate_whenAuthError_mapsToAuthRequired() {
        Pod pullError = waitingPod("ImagePullBackOff", "pull access denied for ghcr.io/private/fn");
        K8sMocks mocks = mockK8s(pullError, null);

        KubernetesImageValidator validator = new KubernetesImageValidator(
                mocks.provider,
                new KubernetesProperties("nanofaas", null)
        );

        assertThatThrownBy(() -> validator.validate(deploymentSpec("fn", "ghcr.io/private/fn:v1", null)))
                .isInstanceOf(ImageValidationException.class)
                .extracting(ex -> ((ImageValidationException) ex).errorCode())
                .isEqualTo("IMAGE_PULL_AUTH_REQUIRED");
    }

    @Test
    void validate_whenInvalidImageNameReason_mapsToNotFound() {
        Pod pullError = waitingPod("InvalidImageName", "invalid image reference");
        K8sMocks mocks = mockK8s(pullError, null);

        KubernetesImageValidator validator = new KubernetesImageValidator(
                mocks.provider,
                new KubernetesProperties("nanofaas", null)
        );

        assertThatThrownBy(() -> validator.validate(deploymentSpec("fn", "not_a_valid_ref", null)))
                .isInstanceOf(ImageValidationException.class)
                .extracting(ex -> ((ImageValidationException) ex).errorCode())
                .isEqualTo("IMAGE_NOT_FOUND");
    }

    @Test
    void validate_usesClientNamespaceWhenPropertyIsBlank() {
        K8sMocks mocks = mockK8s(runningPod(), "team-a");

        KubernetesImageValidator validator = new KubernetesImageValidator(
                mocks.provider,
                new KubernetesProperties("  ", null)
        );

        validator.validate(deploymentSpec("fn", "ghcr.io/example/fn:v1", null));

        verify(mocks.pods, atLeastOnce()).inNamespace("team-a");
    }

    @Test
    void validate_usesDefaultNamespaceWhenNoNamespaceAvailable() {
        K8sMocks mocks = mockK8s(runningPod(), null);

        KubernetesImageValidator validator = new KubernetesImageValidator(
                mocks.provider,
                new KubernetesProperties(null, null)
        );

        validator.validate(deploymentSpec("fn", "ghcr.io/example/fn:v1", null));

        verify(mocks.pods, atLeastOnce()).inNamespace("default");
    }

    @Test
    void validate_trimsAndFiltersImagePullSecrets() {
        K8sMocks mocks = mockK8s(runningPod(), null);

        KubernetesImageValidator validator = new KubernetesImageValidator(
                mocks.provider,
                new KubernetesProperties("nanofaas", null)
        );

        validator.validate(deploymentSpec(
                "fn",
                "ghcr.io/example/fn:v1",
                List.of("  regcred-a  ", "", "  ", "regcred-b")
        ));

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(Pod.class);
        verify(mocks.pods).resource(captor.capture());
        Pod created = captor.getValue();
        assertThat(created.getSpec().getImagePullSecrets())
                .extracting(ref -> ref.getName())
                .containsExactly("regcred-a", "regcred-b");
    }

    @SuppressWarnings("unchecked")
    private K8sMocks mockK8s(Pod observedPod, String clientNamespace) {
        ObjectProvider<KubernetesClient> provider = mock(ObjectProvider.class);
        KubernetesClient client = mock(KubernetesClient.class);
        MixedOperation<Pod, PodList, PodResource> pods = mock(MixedOperation.class);
        PodResource podResource = mock(PodResource.class);
        PodResource namedPod = mock(PodResource.class);

        when(provider.getObject()).thenReturn(client);
        when(client.pods()).thenReturn(pods);
        when(client.getNamespace()).thenReturn(clientNamespace);
        when(pods.inNamespace(anyString())).thenReturn(pods);
        when(pods.resource(any(Pod.class))).thenReturn(podResource);
        when(pods.withName(anyString())).thenReturn(namedPod);
        when(namedPod.get()).thenReturn(observedPod);

        return new K8sMocks(provider, client, pods, podResource, namedPod);
    }

    private Pod runningPod() {
        return new PodBuilder()
                .withNewStatus()
                .withPhase("Running")
                .endStatus()
                .build();
    }

    private Pod waitingPod(String reason, String message) {
        return new PodBuilder()
                .withNewStatus()
                .withPhase("Pending")
                .withContainerStatuses(new ContainerStatusBuilder()
                        .withState(new ContainerStateBuilder()
                                .withNewWaiting()
                                .withReason(reason)
                                .withMessage(message)
                                .endWaiting()
                                .build())
                        .build())
                .endStatus()
                .build();
    }

    private FunctionSpec deploymentSpec(String name, String image, List<String> imagePullSecrets) {
        return new FunctionSpec(
                name,
                image,
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
                null,
                imagePullSecrets
        );
    }

    private record K8sMocks(
            ObjectProvider<KubernetesClient> provider,
            KubernetesClient client,
            MixedOperation<Pod, PodList, PodResource> pods,
            PodResource podResource,
            PodResource namedPod
    ) { }
}
