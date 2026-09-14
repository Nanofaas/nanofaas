package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProviderResolver;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentReadiness;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatusSnapshot;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionOperationLocks;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistry;
import it.unimib.datai.nanofaas.controlplane.registry.ManagedDeploymentCoordinator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

import java.time.InstantSource;

/**
 * What the dispatch path and the function service need when no managed deployment provider is
 * selected, so that neither has to invent it for itself.
 *
 * <p>Both beans replace a fallback that used to be built inside a consumer. The dispatch path
 * treated a missing wake-up gate as a {@code null} to branch on; it now receives
 * {@link DeploymentReadiness#immediate()}, which says the same thing as a value. More importantly
 * {@code FunctionService} used to construct its own {@link ManagedDeploymentCoordinator} when no
 * bean existed, and that constructor creates a replica snapshot owning two refresh pools — an
 * object outside the context, so nothing ever closed it. The coordinator here is a bean with a
 * snapshot that owns no pool at all, which is the honest shape for a control plane that has no
 * backend to read replicas from: every observation is UNAVAILABLE rather than a fabricated zero.</p>
 *
 * <p>Ordered last, and after the managed orchestration, so that its missing-bean conditions see
 * the managed beans when a provider is present. Both configurations must have lowest precedence:
 * a default-order fallback can pull its managed predecessor forward during dependency sorting,
 * before an optional provider's auto-configuration has registered its bean.</p>
 */
@AutoConfiguration
@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE)
@AutoConfigureAfter(ManagedDeploymentOrchestrationAutoConfiguration.class)
public class UnmanagedDeploymentDefaultsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(DeploymentReadiness.class)
    public DeploymentReadiness immediateDeploymentReadiness() {
        return DeploymentReadiness.immediate();
    }

    @Bean
    @ConditionalOnMissingBean(ManagedDeploymentCoordinator.class)
    public ManagedDeploymentCoordinator unmanagedDeploymentCoordinator(DeploymentProviderResolver resolver,
                                                                       FunctionRegistry registry,
                                                                       FunctionOperationLocks locks) {
        return new ManagedDeploymentCoordinator(resolver, registry, locks,
                ReplicaStatusSnapshot.withoutRefreshCapacity(InstantSource.system()));
    }
}
