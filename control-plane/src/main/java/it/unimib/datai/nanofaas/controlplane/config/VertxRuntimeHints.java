package it.unimib.datai.nanofaas.controlplane.config;

import io.fabric8.kubernetes.api.model.DeleteOptions;
import io.fabric8.kubernetes.api.model.Pod;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Registers Vert.x resource files for GraalVM native-image inclusion.
 * <p>
 * Spring Boot AOT processing overwrites static {@code resource-config.json} files
 * placed in {@code META-INF/native-image/}, so we use {@link RuntimeHintsRegistrar}
 * which gets properly merged with the AOT-generated configuration.
 * <p>
 * Without this, the native binary crashes at startup with:
 * {@code IllegalStateException: Cannot find vertx-version.txt on classpath}
 */
@Configuration
@ImportRuntimeHints(VertxRuntimeHints.VertxResourceHints.class)
public class VertxRuntimeHints {

    private static final List<String> FABRIC8_MODEL_PACKAGES = List.of(
            "io.fabric8.kubernetes.api.model",
            "io.fabric8.kubernetes.api.model.apps",
            "io.fabric8.kubernetes.api.model.autoscaling.v2"
    );

    static class VertxResourceHints implements RuntimeHintsRegistrar {
        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
            hints.resources().registerPattern("META-INF/vertx/*");
            // Fabric8 serializes Pod reflectively when creating validation pods.
            hints.reflection().registerType(Pod.class, MemberCategory.INVOKE_PUBLIC_METHODS);
            // Fabric8 serializes DeleteOptions reflectively when deleting validation pods.
            hints.reflection().registerType(DeleteOptions.class, MemberCategory.INVOKE_PUBLIC_METHODS);
            registerFabric8ModelHints(hints, classLoader);
        }

        private static void registerFabric8ModelHints(RuntimeHints hints, ClassLoader classLoader) {
            for (String pkg : FABRIC8_MODEL_PACKAGES) {
                for (String className : findClassesInPackage(classLoader, pkg)) {
                    registerTypeHint(hints, classLoader, className);
                }
            }
        }

        private static Set<String> findClassesInPackage(ClassLoader classLoader, String packageName) {
            String packagePath = packageName.replace('.', '/');
            Set<String> classNames = new LinkedHashSet<>();
            try {
                Enumeration<URL> resources = classLoader.getResources(packagePath);
                while (resources.hasMoreElements()) {
                    URL url = resources.nextElement();
                    if ("jar".equals(url.getProtocol())) {
                        collectFromJar(url, packagePath, classNames);
                    } else if ("file".equals(url.getProtocol())) {
                        collectFromDirectory(url, packagePath, classNames);
                    }
                }
            } catch (IOException ignored) {
                // Best effort: explicit hints above still cover known hotspots.
            }
            return classNames;
        }

        private static void collectFromJar(URL url, String packagePath, Set<String> out) {
            try {
                JarURLConnection conn = (JarURLConnection) url.openConnection();
                try (JarFile jarFile = conn.getJarFile()) {
                    jarFile.stream()
                            .map(entry -> entry.getName())
                            .filter(name -> name.startsWith(packagePath + "/"))
                            .filter(name -> name.endsWith(".class"))
                            .filter(name -> !name.endsWith("package-info.class"))
                            .filter(name -> !name.endsWith("module-info.class"))
                            .map(name -> name.substring(0, name.length() - 6).replace('/', '.'))
                            .forEach(out::add);
                }
            } catch (IOException ignored) {
                // Ignore and continue.
            }
        }

        private static void collectFromDirectory(URL url, String packagePath, Set<String> out) {
            try {
                Path packageDir = Paths.get(url.toURI());
                try (Stream<Path> files = Files.walk(packageDir)) {
                    files.filter(Files::isRegularFile)
                            .map(path -> packageDir.relativize(path).toString())
                            .filter(name -> name.endsWith(".class"))
                            .filter(name -> !name.endsWith("package-info.class"))
                            .filter(name -> !name.endsWith("module-info.class"))
                            .map(name -> packagePath + "/" + name.replace('\\', '/'))
                            .map(name -> name.substring(0, name.length() - 6).replace('/', '.'))
                            .forEach(out::add);
                }
            } catch (IOException | URISyntaxException ignored) {
                // Ignore and continue.
            }
        }

        private static void registerTypeHint(RuntimeHints hints, ClassLoader classLoader, String className) {
            try {
                Class<?> type = Class.forName(className, false, classLoader);
                if (type.isInterface() || type.isAnnotation()) {
                    return;
                }
                hints.reflection().registerType(
                        type,
                        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                        MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                        MemberCategory.INVOKE_PUBLIC_METHODS
                );
            } catch (ClassNotFoundException | LinkageError ignored) {
                // Ignore problematic classes and keep best-effort registration.
            }
        }
    }
}
