package it.unimib.datai.nanofaas.controlplane;

import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerControl;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingStrategy;
import it.unimib.datai.nanofaas.controlplane.service.InvocationEnqueuer;
import it.unimib.datai.nanofaas.controlplane.service.RetryScheduler;
import it.unimib.datai.nanofaas.execution.SchedulerEngine;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadCapacityController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 8 (issue #208): both scheduler strategies compose around ONE engine, ONE control surface
 * and ONE lifecycle, regardless of how many {@code SchedulingStrategy} beans are on the
 * classpath. Written RED first against the pre-Task-8 auto-configurations, where async-queue and
 * sync-queue each registered their own worker and their own {@code WorkloadCapacityController}:
 * with both modules selected that baseline either fails the context outright
 * ({@code NoUniqueBeanDefinitionException} on the duplicate controller) or, before this task's
 * composition existed at all, simply has no {@link SchedulerEngine}/{@link SchedulerControl} bean
 * to find (both counts land on 0, not 1). Either way the assertions below fail against that
 * baseline and pass once {@code SchedulerConfiguration} exists.
 *
 * <p>The retired {@code Scheduler}/{@code SyncScheduler} types are asserted absent by fully
 * qualified name rather than by import: they live in different module packages, only one of
 * which may be on this test's compile classpath for a given {@code -PcontrolPlaneModules}
 * profile run, and Task 13 deletes the classes outright. A name that is not even on the
 * classpath counts as "absent" here, exactly like a bean that is on the classpath but not
 * registered.
 */
@SpringBootTest(classes = ControlPlaneApplication.class)
class SchedulerCompositionTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void composesExactlyOneEngineControlAndRetryScheduler() {
        assertThat(context.getBeansOfType(InvocationEnqueuer.class)).hasSize(1);
        if (context.getBeansOfType(SchedulingStrategy.class).isEmpty()) {
            // No queue module on this profile's classpath at all: SchedulerConfiguration does
            // not activate, matching the pre-Task-8 "direct" admission profile exactly — no
            // scheduler namespace, no engine, no retry scheduler beyond the core's own.
            assertThat(context.getBeansOfType(SchedulerEngine.class)).isEmpty();
            assertThat(context.getBeansOfType(SchedulerControl.class)).isEmpty();
            return;
        }
        assertThat(context.getBeansOfType(SchedulerEngine.class)).hasSize(1);
        assertThat(context.getBeansOfType(SchedulerControl.class)).hasSize(1);
        assertThat(context.getBeansOfType(RetryScheduler.class)).hasSize(1);
    }

    @Test
    void publishesExactlyOneWorkloadCapacityControllerWhenAQueueModuleIsPresent() {
        // Both async-queue and sync-queue used to publish their own; the composed world has
        // exactly one, in SchedulerConfiguration, regardless of how many strategies are present.
        // With no queue module at all, nothing has ever published this bean (pre-Task-8 either).
        int expected = context.getBeansOfType(SchedulingStrategy.class).isEmpty() ? 0 : 1;
        assertThat(context.getBeansOfType(WorkloadCapacityController.class)).hasSize(expected);
    }

    @Test
    void hasNoLegacyPerModuleSchedulerBeans() throws Exception {
        assertNoBeanOfType(context, "it.unimib.datai.nanofaas.modules.asyncqueue.Scheduler");
        assertNoBeanOfType(context, "it.unimib.datai.nanofaas.modules.syncqueue.scheduler.SyncScheduler");
    }

    @Test
    void hasAtMostOneSchedulingSmartLifecycle() {
        // Only SchedulerLifecycleAdapter drives the engine's worker; the retired per-module
        // schedulers were themselves SmartLifecycle beans, so a stray second one here would mean
        // an old worker is still being started alongside the composed engine. With no queue
        // module on the classpath at all, SchedulerConfiguration does not activate and there is
        // none — zero is the correct count there, not one.
        long schedulingLifecycles = context.getBeansOfType(SmartLifecycle.class).values().stream()
                .filter(bean -> bean.getClass().getName().toLowerCase(java.util.Locale.ROOT).contains("scheduler"))
                .count();
        long expected = context.getBeansOfType(SchedulingStrategy.class).isEmpty() ? 0 : 1;
        assertThat(schedulingLifecycles).isEqualTo(expected);
    }

    private static void assertNoBeanOfType(ApplicationContext context, String className) throws Exception {
        Class<?> type;
        try {
            type = Class.forName(className, false, SchedulerCompositionTest.class.getClassLoader());
        } catch (ClassNotFoundException notOnClasspath) {
            // The class is not even on this profile's classpath: trivially absent as a bean too.
            return;
        }
        assertThat(context.getBeansOfType(type)).isEmpty();
    }
}
