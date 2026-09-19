package it.unimib.datai.nanofaas.modules.containerddeploymentprovider;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContainerdPropertiesTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ContainerdProperties.class)
    static class PropertiesConfiguration {}

    @Test
    void defaultsResolveFromServiceUserEnvironment() {
        var properties = ContainerdProperties.defaults(Map.of(
                "HOME", "/home/service", "XDG_RUNTIME_DIR", "/run/user/1000"));
        assertThat(properties.socketPath()).isEqualTo("/run/user/1000/containerd/containerd.sock");
        assertThat(properties.namespace()).isEqualTo("nanofaas");
        assertThat(properties.runtimeBinary()).isEqualTo("crun");
        assertThat(properties.networkName()).isEqualTo("nanofaas");
        assertThat(properties.cniConfigDirectory()).isEqualTo(Path.of("/home/service/.config/cni/net.d"));
        assertThat(properties.cniCacheDirectory()).isEqualTo(Path.of("/home/service/.local/share/nanofaas/cni"));
        assertThat(properties.stateDirectory()).isEqualTo(Path.of("/home/service/.local/share/nanofaas/containerd"));
        assertThat(properties.systemdCgroup()).isTrue();
        assertThat(properties.cgroupsPath()).isEqualTo("user.slice");
    }

    @Test
    void missingRuntimeDirectoryHasActionableDiagnostic() {
        assertThatThrownBy(() -> ContainerdProperties.defaults(Map.of("HOME", "/home/service")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("XDG_RUNTIME_DIR");
    }

    @Test
    void applicationContextBindsBootCanonicalEnvironmentForm() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfiguration.class)
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("systemEnvironment", Map.ofEntries(
                                Map.entry("NANOFAAS_CONTAINERD_SOCKETPATH", "/run/user/4242/containerd/containerd.sock"),
                                Map.entry("NANOFAAS_CONTAINERD_NAMESPACE", "canonical-test"),
                                Map.entry("NANOFAAS_CONTAINERD_RUNTIMEBINARY", "test-crun"),
                                Map.entry("NANOFAAS_CONTAINERD_SNAPSHOTTER", "test-native"),
                                Map.entry("NANOFAAS_CONTAINERD_NETWORKNAME", "canonical-net"),
                                Map.entry("NANOFAAS_CONTAINERD_CNIPLUGINDIRECTORY", "/test/cni/bin"),
                                Map.entry("NANOFAAS_CONTAINERD_CNICONFIGDIRECTORY", "/test/cni/conf"),
                                Map.entry("NANOFAAS_CONTAINERD_CNICACHEDIRECTORY", "/test/cni/cache"),
                                Map.entry("NANOFAAS_CONTAINERD_STATEDIRECTORY", "/test/state"),
                                Map.entry("NANOFAAS_CONTAINERD_CNIPLUGINTIMEOUT", "19s"),
                                Map.entry("NANOFAAS_CONTAINERD_STOPTIMEOUT", "12s"),
                                Map.entry("NANOFAAS_CONTAINERD_AVAILABILITYTIMEOUT", "5s"),
                                Map.entry("NANOFAAS_CONTAINERD_SYSTEMDCGROUP", "false"),
                                Map.entry("NANOFAAS_CONTAINERD_CGROUPSPATH", "canonical.slice"),
                                Map.entry("NANOFAAS_CONTAINERD_CPUSET", "2-3"),
                                Map.entry("NANOFAAS_CONTAINERD_CALLBACKURL", "http://10.90.0.1:8080"),
                                Map.entry("NANOFAAS_CONTAINERD_BINDHOST", "0.0.0.0"),
                                Map.entry("NANOFAAS_CONTAINERD_READINESSTIMEOUT", "22s"),
                                Map.entry("NANOFAAS_CONTAINERD_READINESSPOLLINTERVAL", "350ms")))))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ContainerdProperties properties = context.getBean(ContainerdProperties.class);
                    assertThat(properties.socketPath()).isEqualTo("/run/user/4242/containerd/containerd.sock");
                    assertThat(properties.namespace()).isEqualTo("canonical-test");
                    assertThat(properties.networkName()).isEqualTo("canonical-net");
                    assertThat(properties.runtimeBinary()).isEqualTo("test-crun");
                    assertThat(properties.snapshotter()).isEqualTo("test-native");
                    assertThat(properties.cniPluginDirectory()).isEqualTo(Path.of("/test/cni/bin"));
                    assertThat(properties.cniConfigDirectory()).isEqualTo(Path.of("/test/cni/conf"));
                    assertThat(properties.cniCacheDirectory()).isEqualTo(Path.of("/test/cni/cache"));
                    assertThat(properties.stateDirectory()).isEqualTo(Path.of("/test/state"));
                    assertThat(properties.cniPluginTimeout()).isEqualTo(java.time.Duration.ofSeconds(19));
                    assertThat(properties.stopTimeout()).isEqualTo(java.time.Duration.ofSeconds(12));
                    assertThat(properties.availabilityTimeout()).isEqualTo(java.time.Duration.ofSeconds(5));
                    assertThat(properties.systemdCgroup()).isFalse();
                    assertThat(properties.cgroupsPath()).isEqualTo("canonical.slice");
                    assertThat(properties.cpuset()).isEqualTo("2-3");
                    assertThat(properties.callbackUrl()).isEqualTo("http://10.90.0.1:8080");
                    assertThat(properties.bindHost()).isEqualTo("0.0.0.0");
                    assertThat(properties.readinessTimeout()).isEqualTo(java.time.Duration.ofSeconds(22));
                    assertThat(properties.readinessPollInterval()).isEqualTo(java.time.Duration.ofMillis(350));
                });
    }
}
