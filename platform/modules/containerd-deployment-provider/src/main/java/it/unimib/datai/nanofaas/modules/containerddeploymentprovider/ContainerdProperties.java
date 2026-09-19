package it.unimib.datai.nanofaas.modules.containerddeploymentprovider;

import io.nanofaas.containerd.cni.CniContainerNetwork;
import io.nanofaas.containerd.spi.ContainerdClient;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

@ConfigurationProperties(prefix = "nanofaas.containerd")
public record ContainerdProperties(
        String socketPath,
        String namespace,
        String runtimeBinary,
        String snapshotter,
        String networkName,
        Path cniPluginDirectory,
        Path cniConfigDirectory,
        Path cniCacheDirectory,
        Path stateDirectory,
        Duration cniPluginTimeout,
        Duration stopTimeout,
        Duration availabilityTimeout,
        Boolean systemdCgroup,
        String cgroupsPath,
        String cpuset,
        String callbackUrl,
        String bindHost,
        Duration readinessTimeout,
        Duration readinessPollInterval
) {
    @ConstructorBinding
    public ContainerdProperties {
        Map<String, String> env = System.getenv();
        if (socketPath == null || socketPath.isBlank()) {
            socketPath = required(env, "XDG_RUNTIME_DIR") + "/containerd/containerd.sock";
        }
        String home = null;
        if (cniConfigDirectory == null || cniCacheDirectory == null || stateDirectory == null) {
            home = required(env, "HOME");
        }
        if (cniConfigDirectory == null) cniConfigDirectory = Path.of(home, ".config/cni/net.d");
        if (cniCacheDirectory == null) cniCacheDirectory = Path.of(home, ".local/share/nanofaas/cni");
        if (stateDirectory == null) stateDirectory = Path.of(home, ".local/share/nanofaas/containerd");
        if (cniPluginDirectory == null) cniPluginDirectory = Path.of("/opt/cni/bin");
        namespace = defaultString(namespace, "nanofaas");
        runtimeBinary = defaultString(runtimeBinary, "crun");
        snapshotter = defaultString(snapshotter, "native");
        networkName = defaultString(networkName, "nanofaas");
        cgroupsPath = defaultString(cgroupsPath, "user.slice");
        bindHost = defaultString(bindHost, "127.0.0.1");
        cniPluginTimeout = positive(cniPluginTimeout, Duration.ofSeconds(30), "cniPluginTimeout");
        stopTimeout = positive(stopTimeout, Duration.ofSeconds(10), "stopTimeout");
        availabilityTimeout = positive(availabilityTimeout, Duration.ofSeconds(3), "availabilityTimeout");
        readinessTimeout = positive(readinessTimeout, Duration.ofSeconds(20), "readinessTimeout");
        readinessPollInterval = positive(readinessPollInterval, Duration.ofMillis(250), "readinessPollInterval");
        systemdCgroup = systemdCgroup == null ? Boolean.TRUE : systemdCgroup;
        cpuset = cpuset == null || cpuset.isBlank() ? null : cpuset;
        callbackUrl = callbackUrl == null || callbackUrl.isBlank() ? null : callbackUrl;
        for (Path path : new Path[]{Path.of(socketPath), cniPluginDirectory, cniConfigDirectory,
                cniCacheDirectory, stateDirectory}) {
            if (!path.isAbsolute()) throw new IllegalArgumentException("containerd path must be absolute: " + path);
        }
    }

    public static ContainerdProperties defaults(Map<String, String> env) {
        String home = required(env, "HOME");
        String runtime = required(env, "XDG_RUNTIME_DIR");
        return new ContainerdProperties(runtime + "/containerd/containerd.sock", null, null, null, null,
                null, Path.of(home, ".config/cni/net.d"), Path.of(home, ".local/share/nanofaas/cni"),
                Path.of(home, ".local/share/nanofaas/containerd"), null, null, null, null, null,
                null, null, null, null, null);
    }

    /** Called once by the selected module's Spring configuration; the client is shared by all replicas. */
    public ContainerdClient newClient() {
        requireDirectory(cniPluginDirectory, "nanofaas.containerd.cni-plugin-directory");
        requireDirectory(cniConfigDirectory, "nanofaas.containerd.cni-config-directory");
        return ContainerdClient.builder()
                .socketPath(socketPath)
                .namespace(namespace)
                .runtimeBinaryName(runtimeBinary)
                .snapshotter(snapshotter)
                .systemdCgroup(systemdCgroup)
                .stopTimeout(stopTimeout)
                .stateDirectory(stateDirectory)
                .network(CniContainerNetwork.builder()
                        .pluginDir(cniPluginDirectory)
                        .configDir(cniConfigDirectory)
                        .cacheDir(cniCacheDirectory)
                        .pluginTimeout(cniPluginTimeout)
                        .build())
                .build();
    }

    private static void requireDirectory(Path path, String setting) {
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException(setting + " must point to an existing directory: " + path);
        }
    }

    private static String required(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " is required for rootless containerd defaults; configure it or set explicit nanofaas.containerd paths");
        }
        return value;
    }

    private static String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Duration positive(Duration value, Duration fallback, String name) {
        if (value == null) return fallback;
        if (value.isNegative() || value.isZero()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}
