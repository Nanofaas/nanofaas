package it.unimib.datai.nanofaas.controlplane.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aot.hint.RuntimeHints;

import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class VertxRuntimeHintsBranchTest {

    @TempDir
    Path tempDir;

    @Test
    void findClassesInPackage_collectsFromDirectoryAndSkipsMetadataClasses() throws Exception {
        Path packageDir = tempDir.resolve("demo/pkg");
        Files.createDirectories(packageDir);
        Files.write(packageDir.resolve("Alpha.class"), new byte[0]);
        Files.write(packageDir.resolve("Beta$Inner.class"), new byte[0]);
        Files.write(packageDir.resolve("package-info.class"), new byte[0]);
        Files.write(packageDir.resolve("module-info.class"), new byte[0]);

        Method findClasses = VertxRuntimeHints.VertxResourceHints.class
                .getDeclaredMethod("findClassesInPackage", ClassLoader.class, String.class);
        findClasses.setAccessible(true);

        Set<String> classes;
        try (URLClassLoader classLoader = new URLClassLoader(new java.net.URL[]{tempDir.toUri().toURL()}, null)) {
            @SuppressWarnings("unchecked")
            Set<String> result = (Set<String>) findClasses.invoke(null, classLoader, "demo.pkg");
            classes = result;
        }

        assertThat(classes).contains("demo.pkg.Alpha", "demo.pkg.Beta$Inner");
        assertThat(classes).doesNotContain("demo.pkg.package-info", "demo.pkg.module-info");
    }

    @Test
    void registerTypeHint_handlesInterfaceAndMissingClassSafely() throws Exception {
        RuntimeHints hints = new RuntimeHints();
        Method registerTypeHint = VertxRuntimeHints.VertxResourceHints.class
                .getDeclaredMethod("registerTypeHint", RuntimeHints.class, ClassLoader.class, String.class);
        registerTypeHint.setAccessible(true);

        ClassLoader classLoader = getClass().getClassLoader();
        registerTypeHint.invoke(null, hints, classLoader, "java.lang.Runnable");
        registerTypeHint.invoke(null, hints, classLoader, "com.example.DoesNotExist");
        registerTypeHint.invoke(null, hints, classLoader, "java.lang.String");

        assertThat(hints.reflection().getTypeHint(Runnable.class)).isNull();
        assertThat(hints.reflection().getTypeHint(String.class)).isNotNull();
    }
}
