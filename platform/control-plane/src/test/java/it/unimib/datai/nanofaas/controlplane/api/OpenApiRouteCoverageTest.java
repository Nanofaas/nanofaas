package it.unimib.datai.nanofaas.controlplane.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Points at {@code openapi.yaml} when a route is added without documenting it. The spec is
 * hand-written and nothing generates from it, so this is a signpost, not a contract check: it
 * compares routes only, and says nothing about request/response schemas.
 */
class OpenApiRouteCoverageTest {

    /** Callbacks the control plane exposes to its own function pods, not part of the public API. */
    private static final String INTERNAL_PREFIX = "/v1/internal/";

    @Test
    void everyPublicRouteOfTheCoreControllersIsInTheSpec() {
        Set<String> documented = documentedOperations();

        Set<String> undocumented = new TreeSet<>(declaredRoutes());
        undocumented.removeAll(documented);

        assertThat(undocumented)
                .as("routes missing from openapi.yaml — add them there, or move them under %s if internal",
                        INTERNAL_PREFIX)
                .isEmpty();
    }

    private static Set<String> declaredRoutes() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        Set<String> routes = new TreeSet<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents(OpenApiRouteCoverageTest.class.getPackageName())) {
            Class<?> controller = loadClass(candidate.getBeanClassName());
            String prefix = firstPath(AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class));
            for (var method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                String path = prefix + firstPath(mapping);
                if (path.startsWith(INTERNAL_PREFIX)) {
                    continue;
                }
                for (RequestMethod verb : mapping.method()) {
                    routes.add(operation(verb.name(), path));
                }
            }
        }
        assertThat(routes).as("no routes found — the scanner or the package name is wrong").isNotEmpty();
        return routes;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> documentedOperations() {
        Path spec = repoRoot().resolve("openapi/core.yaml");
        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(spec)) {
            root = new Yaml().load(in);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read " + spec, e);
        }

        Set<String> operations = new TreeSet<>();
        Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) root.get("paths");
        paths.forEach((path, verbs) -> verbs.keySet().forEach(verb -> operations.add(operation(verb, path))));
        return operations;
    }

    private static String operation(String verb, String path) {
        return verb.toLowerCase(Locale.ROOT) + " " + path;
    }

    private static String firstPath(RequestMapping mapping) {
        if (mapping == null || mapping.path().length == 0) {
            return "";
        }
        return mapping.path()[0];
    }

    private static Class<?> loadClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("settings.gradle"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Could not locate the repository root");
        }
        return current;
    }
}
