package it.unimib.datai.nanofaas.modules.k8s;

import it.unimib.datai.nanofaas.controlplane.ControlPlaneApplication;
import it.unimib.datai.nanofaas.controlplane.registry.ManagedDeploymentCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The other half of what the internal scaler needs. InternalScaler.start() returns
 * early when no {@code ManagedDeploymentCoordinator} is injected - it logs one line
 * and never schedules its loop - so this bean going missing would stop autoscaling
 * as completely as the module not loading, and just as quietly.
 */
@SpringBootTest(classes = ControlPlaneApplication.class)
class K8sDeploymentProviderContextTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void publishesTheDeploymentCoordinatorTheScalerNeeds() {
        assertThat(context.getBeansOfType(ManagedDeploymentCoordinator.class)).hasSize(1);
    }
}
