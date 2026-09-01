package it.unimib.datai.nanofaas.controlplane.config;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

/**
 * Micrometer's ProcessorMetrics reads OperatingSystemMXBean.getProcessCpuTime() reflectively to
 * feed the process.cpu.time gauge, but micrometer-core ships no reachability metadata for that
 * method — its bundled reflect-config.json covers only getCpuLoad, getProcessCpuLoad and
 * getSystemCpuLoad. Under GraalVM the gauge therefore throws MissingReflectionRegistrationError
 * the moment something reads it, which is every scrape of /actuator/prometheus. The endpoint fails
 * closed, Prometheus records no function_* series at all, and the app looks perfectly healthy
 * because nothing else touches that method.
 *
 * <p>Drop this registrar once micrometer-core registers the method itself.
 * See: https://github.com/micrometer-metrics/micrometer — ProcessorMetrics
 */
public class ProcessorMetricsRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.reflection().registerType(
                TypeReference.of("com.sun.management.OperatingSystemMXBean"),
                builder -> builder.withMembers(MemberCategory.INVOKE_PUBLIC_METHODS));
    }
}
