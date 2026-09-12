package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;

/**
 * Managed deployment orchestration exists exactly when a managed deployment provider does.
 *
 * <p>A LOCAL/EXTERNAL control plane consequently builds no replica-refresh pools, no wake-up
 * executor, no timeout scheduler and no wake-up gate: none of them could ever do any work there,
 * so every one of those threads would idle for the process's lifetime and give shutdown something
 * to drain that had never run.</p>
 *
 * <p>This is an auto-configuration at the lowest precedence, not a component-scanned
 * {@code @Configuration}, for the reason already documented on
 * {@link InvocationEnqueuerAutoConfiguration}: {@code @ConditionalOnBean} only sees what is
 * registered when it is evaluated, and component-scanned classes are processed BEFORE any
 * auto-configuration. The provider modules publish their providers from {@code @AutoConfiguration}
 * classes, so a scanned configuration would have evaluated this condition before any provider
 * existed and disabled managed deployment in every profile.</p>
 */
@AutoConfiguration
@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnBean(ManagedDeploymentProvider.class)
@Import(ManagedDeploymentOrchestration.class)
public class ManagedDeploymentOrchestrationAutoConfiguration {
}
