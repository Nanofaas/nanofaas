package it.unimib.datai.nanofaas.controlplane.config;

import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentTarget;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatus;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatusSnapshot;
import it.unimib.datai.nanofaas.controlplane.registry.ManagedDeploymentCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class ReplicaStatusSnapshotConfigurationTest {

    @Test
    void contextDestructionClosesTheConfiguredOwnerOnceAndReleasesPendingRefreshes() throws Exception {
        // Break: removing the bean's destruction ownership leaves the caller and provider running;
        // giving the injected coordinator ownership would create a second shutdown owner.
        AtomicInteger destructions = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var context = new AnnotationConfigApplicationContext();
        context.getBeanFactory().addBeanPostProcessor(new DestructionAwareBeanPostProcessor() {
            @Override
            public void postProcessBeforeDestruction(Object bean, String name) {
                if (bean instanceof ReplicaStatusSnapshot) {
                    destructions.incrementAndGet();
                }
            }
        });
        context.register(ReplicaStatusSnapshotConfiguration.class);
        context.registerBean(ManagedDeploymentCoordinator.class, () -> new ManagedDeploymentCoordinator(
                null, null, null, context.getBean(ReplicaStatusSnapshot.class)));
        context.refresh();
        ReplicaStatusSnapshot snapshot = context.getBean(ReplicaStatusSnapshot.class);
        try (var callers = Executors.newSingleThreadExecutor()) {
            var pending = callers.submit(() -> snapshot.refresh(new ManagedDeploymentTarget("pending", "k8s"), t -> {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("context did not stop the provider");
                    }
                } catch (InterruptedException _) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
                return new ReplicaStatus(9, 9);
            }));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            context.close();
            context.close();

            assertThatThrownBy(() -> pending.get(2, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(ReplicaStatusSnapshot.ReplicaStatusUnavailableException.class);
            assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                while (!snapshot.isTerminated()) {
                    Thread.onSpinWait();
                }
            });
            assertThat(destructions).hasValue(1);
            assertThat(snapshot.entryCount()).isZero();
        } finally {
            release.countDown();
            context.close();
        }
    }
}
