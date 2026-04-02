package it.unimib.datai.nanofaas.modules.k8s.e2e;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Probe;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class K8sE2eDeploymentSpecTest {

    @Test
    void controlPlaneDeployment_exposesManagementHealthProbes() throws Exception {
        Container container = containerFrom("controlPlaneDeployment");

        Probe readiness = container.getReadinessProbe();
        Probe liveness = container.getLivenessProbe();

        assertAll(
                () -> assertNotNull(readiness, "control-plane readinessProbe should be configured"),
                () -> assertNotNull(liveness, "control-plane livenessProbe should be configured"),
                () -> assertEquals("/actuator/health/readiness", readiness.getHttpGet().getPath()),
                () -> assertEquals(Integer.valueOf(8081), readiness.getHttpGet().getPort().getIntVal()),
                () -> assertEquals("/actuator/health/liveness", liveness.getHttpGet().getPath()),
                () -> assertEquals(Integer.valueOf(8081), liveness.getHttpGet().getPort().getIntVal())
        );
    }

    @Test
    void functionRuntimeDeployment_exposesHttpReadinessProbe() throws Exception {
        Container container = containerFrom("functionRuntimeDeployment");
        Probe readiness = container.getReadinessProbe();

        assertAll(
                () -> assertNotNull(readiness, "function-runtime readinessProbe should be configured"),
                () -> assertEquals("/actuator/health", readiness.getHttpGet().getPath()),
                () -> assertEquals(Integer.valueOf(8080), readiness.getHttpGet().getPort().getIntVal())
        );
    }

    private static Container containerFrom(String factoryMethodName) throws Exception {
        Method method = K8sE2eTest.class.getDeclaredMethod(factoryMethodName);
        method.setAccessible(true);
        Deployment deployment = (Deployment) method.invoke(null);
        return deployment.getSpec().getTemplate().getSpec().getContainers().getFirst();
    }
}
