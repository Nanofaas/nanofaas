package it.unimib.datai.nanofaas.controlplane.e2e;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Comparator;

final class E2eTestSupport {

    static final Path PROJECT_ROOT = Path.of("..").toAbsolutePath().normalize();

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
}
