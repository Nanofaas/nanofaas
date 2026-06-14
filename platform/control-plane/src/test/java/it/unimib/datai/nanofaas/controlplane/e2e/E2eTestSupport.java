package it.unimib.datai.nanofaas.controlplane.e2e;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Comparator;
import java.time.Duration;
import java.util.Objects;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

final class E2eTestSupport {

    static final Path PROJECT_ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final String CONTROL_PLANE_IMAGE_OVERRIDE_ENV = "CONTROL_PLANE_IMAGE_OVERRIDE";
    private static final String CONTROL_PLANE_IMAGE_OVERRIDE_PROP = "control.plane.image.override";

    private E2eTestSupport() {
    }

    static String projectVersion() {
        String fromProp = System.getProperty("project.version");
        if (fromProp != null && !fromProp.isBlank()) {
            return fromProp;
        }
        String fromEnv = System.getenv("PROJECT_VERSION");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        return "dev";
    }

    static String versionedImage(String imageName) {
        return "nanofaas/" + imageName + ":" + projectVersion();
    }

    static ControlPlaneContainerPlan resolveControlPlaneContainerPlan() {
        String overrideImage = controlPlaneImageOverride();
        if (overrideImage != null) {
            return ControlPlaneContainerPlan.imageOverride(overrideImage);
        }
        return ControlPlaneContainerPlan.dockerfileBased(
                PROJECT_ROOT.resolve("control-plane/Dockerfile"),
                PROJECT_ROOT.resolve("control-plane/build/libs"),
                "control-plane-");
    }

    static GenericContainer<?> createControlPlaneContainer(Network network) {
        return createControlPlaneContainer(network, Duration.ofSeconds(60));
    }

    static GenericContainer<?> createControlPlaneContainer(Network network, Duration startupTimeout) {
        ControlPlaneContainerPlan plan = resolveControlPlaneContainerPlan();
        GenericContainer<?> controlPlane;
        if (plan.isImageOverride()) {
            controlPlane = new GenericContainer<>(plan.imageOverride());
        } else {
            Path controlPlaneJar = resolveBootJar(plan.moduleBuildLibsDir(), plan.jarPrefix());
            controlPlane = new GenericContainer<>(
                    new ImageFromDockerfile()
                            .withFileFromPath("Dockerfile", plan.dockerfilePath())
                            .withFileFromPath("build/libs/" + controlPlaneJar.getFileName(), controlPlaneJar));
        }

        return controlPlane
                .withExposedPorts(8080, 8081)
                .withNetwork(network)
                .withNetworkAliases("control-plane")
                .withEnv("SYNC_QUEUE_ENABLED", "false")
                .withEnv("NANOFAAS_SYNC_QUEUE_ENABLED", "false")
                .waitingFor(Wait.forHttp("/actuator/health").forPort(8081).withStartupTimeout(startupTimeout));
    }

    static String controlPlaneImageOverride() {
        String fromProp = System.getProperty(CONTROL_PLANE_IMAGE_OVERRIDE_PROP);
        if (fromProp != null && !fromProp.isBlank()) {
            return fromProp;
        }
        String fromEnv = System.getenv(CONTROL_PLANE_IMAGE_OVERRIDE_ENV);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        return null;
    }

    static Path resolveBootJar(Path moduleBuildLibsDir, String jarPrefix) {
        Path appJar = moduleBuildLibsDir.resolve("app.jar");
        if (Files.isRegularFile(appJar)) {
            return appJar;
        }

        List<Path> candidates;
        try (var stream = Files.list(moduleBuildLibsDir)) {
            candidates = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith(jarPrefix))
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .filter(p -> !p.getFileName().toString().endsWith("-plain.jar"))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan jars in " + moduleBuildLibsDir, e);
        }

        if (candidates.isEmpty()) {
            throw new IllegalStateException("No boot jar found in " + moduleBuildLibsDir);
        }

        String expectedName = jarPrefix + "-" + projectVersion() + ".jar";
        return candidates.stream()
                .filter(p -> p.getFileName().toString().equals(expectedName))
                .findFirst()
                .orElseGet(() -> candidates.stream()
                        .max(Comparator
                                .comparingLong(E2eTestSupport::lastModifiedOrMinValue)
                                .thenComparing(p -> p.getFileName().toString()))
                        .orElseThrow(() -> new IllegalStateException("No boot jar found in " + moduleBuildLibsDir)));
    }

    private static long lastModifiedOrMinValue(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return Long.MIN_VALUE;
        }
    }

    static final class ControlPlaneContainerPlan {
        private final String imageOverride;
        private final Path dockerfilePath;
        private final Path moduleBuildLibsDir;
        private final String jarPrefix;

        private ControlPlaneContainerPlan(String imageOverride, Path dockerfilePath, Path moduleBuildLibsDir, String jarPrefix) {
            this.imageOverride = imageOverride;
            this.dockerfilePath = dockerfilePath;
            this.moduleBuildLibsDir = moduleBuildLibsDir;
            this.jarPrefix = jarPrefix;
        }

        static ControlPlaneContainerPlan imageOverride(String image) {
            return new ControlPlaneContainerPlan(Objects.requireNonNull(image, "image"), null, null, null);
        }

        static ControlPlaneContainerPlan dockerfileBased(Path dockerfilePath, Path moduleBuildLibsDir, String jarPrefix) {
            return new ControlPlaneContainerPlan(
                    null,
                    Objects.requireNonNull(dockerfilePath, "dockerfilePath"),
                    Objects.requireNonNull(moduleBuildLibsDir, "moduleBuildLibsDir"),
                    Objects.requireNonNull(jarPrefix, "jarPrefix"));
        }

        boolean isImageOverride() {
            return imageOverride != null;
        }

        String imageOverride() {
            return imageOverride;
        }

        Path dockerfilePath() {
            if (dockerfilePath == null) {
                throw new IllegalStateException("dockerfilePath is only available for dockerfile-based plans.");
            }
            return dockerfilePath;
        }

        Path moduleBuildLibsDir() {
            if (moduleBuildLibsDir == null) {
                throw new IllegalStateException("moduleBuildLibsDir is only available for dockerfile-based plans.");
            }
            return moduleBuildLibsDir;
        }

        String jarPrefix() {
            if (jarPrefix == null) {
                throw new IllegalStateException("jarPrefix is only available for dockerfile-based plans.");
            }
            return jarPrefix;
        }
    }
}
