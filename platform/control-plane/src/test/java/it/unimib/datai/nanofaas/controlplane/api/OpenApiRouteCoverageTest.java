package it.unimib.datai.nanofaas.controlplane.api;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scans every {@code @RestController} under {@code it.unimib.datai.nanofaas} — core API plus
 * whichever optional modules are on the test classpath — and checks each public route is
 * documented in the Gradle-composed contract at {@code META-INF/resources/openapi.yaml} (core
 * document + enabled module fragments), the same file Spring Boot serves at {@code /openapi.yaml}.
 * Route coverage only: request/response schema shape is owned by the composer's own tests and by
 * endpoint tests.
 */
class OpenApiRouteCoverageTest {

    /** Callbacks the control plane exposes to its own function pods, not part of the public API. */
    private static final String INTERNAL_PREFIX = "/v1/internal/";

    private static final String SCAN_ROOT = "it.unimib.datai.nanofaas";

    @Test
    void everyPublicRouteIsInTheComposedSpec() {
        Set<String> documented = documentedOperations();

        Set<String> undocumented = new TreeSet<>(declaredRoutes());
        undocumented.removeAll(documented);

        assertThat(undocumented)
                .as("routes missing from the composed openapi.yaml — document them there, or move them under %s if internal",
                        INTERNAL_PREFIX)
                .isEmpty();
    }

    @Test
    void enabledModuleRoutesAreDocumented() {
        Set<String> selected = selectedModules();
        Assumptions.assumeTrue(selected.contains("build-metadata") && selected.contains("runtime-config"),
                "build-metadata and runtime-config modules are not both selected: " + selected);

        assertThat(declaredRoutes()).contains(
                "get /modules/build-metadata",
                "get /v1/admin/runtime-config");
        assertThat(documentedOperations()).containsAll(declaredRoutes());
    }

    private static Set<String> selectedModules() {
        String modules = System.getProperty("nanofaas.selectedControlPlaneModules", "");
        return Stream.of(modules.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toSet());
    }

    /**
     * Finds every {@code @RestController} under {@link #SCAN_ROOT} by reading class metadata
     * directly, rather than via {@code ClassPathScanningCandidateComponentProvider}: that provider
     * evaluates {@code @Conditional} annotations (like {@code @ConditionalOnProperty} on
     * {@code AdminRuntimeConfigController}) against an empty environment and silently drops
     * controllers it can't prove are active, which is wrong here — a route review needs every
     * declared controller, active or not.
     */
    private static Set<String> declaredRoutes() {
        Set<String> routes = new TreeSet<>();
        var resolver = new PathMatchingResourcePatternResolver();
        var readerFactory = new SimpleMetadataReaderFactory(resolver);
        String pattern = "classpath*:" + SCAN_ROOT.replace('.', '/') + "/**/*.class";
        Resource[] resources;
        try {
            resources = resolver.getResources(pattern);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot scan " + pattern, e);
        }
        for (Resource resource : resources) {
            var metadata = readMetadata(readerFactory, resource).getAnnotationMetadata();
            if (!metadata.isConcrete() || !metadata.isIndependent()
                    || !metadata.getAnnotationTypes().contains(RestController.class.getName())) {
                continue;
            }
            Class<?> controller = loadClass(metadata.getClassName());
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

    private static MetadataReader readMetadata(SimpleMetadataReaderFactory readerFactory, Resource resource) {
        try {
            return readerFactory.getMetadataReader(resource);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + resource, e);
        }
    }

    /**
     * Path items also carry non-verb keys ({@code parameters}, {@code summary}, {@code $ref}),
     * so the verbs have to be recognised rather than assumed. Derived from Spring's own enum
     * so this list cannot drift from the annotations the scan above reads.
     */
    private static final Set<String> HTTP_METHODS = Arrays.stream(RequestMethod.values())
            .map(method -> method.name().toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());

    @SuppressWarnings("unchecked")
    private static Set<String> documentedOperations() {
        Map<String, Object> root;
        String resource = "/META-INF/resources/openapi.yaml";
        try (InputStream in = OpenApiRouteCoverageTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " not found on the test classpath — "
                        + "did composeControlPlaneOpenApi run before processResources?");
            }
            root = new Yaml().load(in);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read " + resource, e);
        }

        Set<String> operations = new TreeSet<>();
        Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) root.get("paths");
        paths.forEach((path, verbs) -> verbs.keySet().stream()
                .filter(HTTP_METHODS::contains)
                .forEach(verb -> operations.add(operation(verb, path))));
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
}
