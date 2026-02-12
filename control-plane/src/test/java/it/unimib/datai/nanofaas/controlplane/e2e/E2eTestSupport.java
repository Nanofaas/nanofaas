package it.unimib.datai.nanofaas.controlplane.e2e;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        try (var stream = Files.list(moduleBuildLibsDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith(jarPrefix))
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .filter(p -> !p.getFileName().toString().endsWith("-plain.jar"))
                    .max(Comparator.comparing(p -> p.getFileName().toString()))
                    .orElseThrow(() -> new IllegalStateException("No boot jar found in " + moduleBuildLibsDir));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan jars in " + moduleBuildLibsDir, e);
        }
    }
}
