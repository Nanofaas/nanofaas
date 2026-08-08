package it.unimib.datai.nanofaas.controlplane.deployment;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManagedDeploymentCoordinatorTest {

    private final ManagedDeploymentProvider provider = mock(ManagedDeploymentProvider.class);
    private final ManagedDeploymentCoordinator coordinator = new ManagedDeploymentCoordinator(
            new DeploymentProviderResolver(List.of(provider), new DeploymentProperties(null))
    );
    private final ManagedDeploymentTarget target = new ManagedDeploymentTarget("fn", "k8s");

    @Test
    void delegatesOperationsUsingTheTargetBackend() {
        when(provider.backendId()).thenReturn("k8s");
        when(provider.getReadyReplicas("fn")).thenReturn(2);
        when(provider.getReplicaStatus("fn")).thenReturn(new ReplicaStatus(3, 2));

        coordinator.setReplicas(target, 3);

        assertThat(coordinator.getReadyReplicas(target)).isEqualTo(2);
        assertThat(coordinator.getReplicaStatus(target)).isEqualTo(new ReplicaStatus(3, 2));
        coordinator.deprovision(target);

        verify(provider).setReplicas("fn", 3);
        verify(provider).deprovision("fn");
    }

    @Test
    void rejectsMissingTargetValues() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ManagedDeploymentTarget("fn", " "));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ManagedDeploymentTarget(null, "k8s"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ManagedDeploymentTarget(" ", "k8s"));
    }
}
