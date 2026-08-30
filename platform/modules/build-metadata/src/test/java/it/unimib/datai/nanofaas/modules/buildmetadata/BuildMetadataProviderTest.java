package it.unimib.datai.nanofaas.modules.buildmetadata;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class BuildMetadataProviderTest {

    private static BuildMetadataProvider provider(Properties props, Map<String, String> env,
                                                    Map<String, String> systemProps, List<String> gcNames) {
        return new BuildMetadataProvider(props, env, systemProps, gcNames);
    }

    @Test
    void normalizesAarch64ToArm64() {
        assertThat(provider(new Properties(), Map.of(), Map.of("os.arch", "aarch64"), List.of("G1 GC"))
                .get().runtime().architecture()).isEqualTo("arm64");
    }

    @Test
    void normalizesAmd64ToX86_64() {
        assertThat(provider(new Properties(), Map.of(), Map.of("os.arch", "amd64"), List.of())
                .get().runtime().architecture()).isEqualTo("x86_64");
    }

    @Test
    void unknownArchitectureIsNull() {
        assertThat(provider(new Properties(), Map.of(), Map.of("os.arch", "sparc"), List.of())
                .get().runtime().architecture()).isNull();
    }

    @Test
    void missingEverythingProducesNullFields() {
        BuildMetadata metadata = provider(new Properties(), Map.of(), Map.of(), List.of()).get();

        assertThat(metadata.revision()).isNull();
        assertThat(metadata.version()).isNull();
        assertThat(metadata.dirty()).isNull();
        assertThat(metadata.modules()).isNull();
        assertThat(metadata.build().type()).isNull();
        assertThat(metadata.build().variant()).isNull();
        assertThat(metadata.build().optimization()).isNull();
        assertThat(metadata.build().baseImages().builder()).isNull();
        assertThat(metadata.build().baseImages().runtime()).isNull();
        assertThat(metadata.runtime().architecture()).isNull();
        assertThat(metadata.runtime().kernelVersion()).isNull();
        assertThat(metadata.runtime().javaVersion()).isNull();
        assertThat(metadata.runtime().vm()).isNull();
        assertThat(metadata.runtime().garbageCollectors()).isNull();
    }

    @Test
    void sortsModulesFromProperties() {
        Properties props = new Properties();
        props.setProperty("modules", "sync-queue,async-queue,build-metadata");

        assertThat(provider(props, Map.of(), Map.of(), List.of()).get().modules())
                .containsExactly("async-queue", "build-metadata", "sync-queue");
    }

    @Test
    void sortsGarbageCollectorNames() {
        assertThat(provider(new Properties(), Map.of(), Map.of(),
                List.of("G1 Old Generation", "G1 Concurrent GC", "G1 Young Generation"))
                .get().runtime().garbageCollectors())
                .containsExactly("G1 Concurrent GC", "G1 Old Generation", "G1 Young Generation");
    }

    @Test
    void kernelVersionComesFromOsVersion() {
        assertThat(provider(new Properties(), Map.of(), Map.of("os.version", "6.8.0-52-generic"), List.of())
                .get().runtime().kernelVersion()).isEqualTo("6.8.0-52-generic");
    }

    @Test
    void vmIsNullWhenJavaVmNameAbsentEvenUnderNativeImageMarker() {
        assertThat(provider(new Properties(), Map.of(),
                Map.of("org.graalvm.nativeimage.imagecode", "runtime"), List.of())
                .get().runtime().vm()).isNull();
    }

    @Test
    void vmComesFromJavaVmNameAlone() {
        assertThat(provider(new Properties(), Map.of(),
                Map.of("java.vm.name", "OpenJDK 64-Bit Server VM"), List.of())
                .get().runtime().vm()).isEqualTo("OpenJDK 64-Bit Server VM");
    }

    @Test
    void buildTypeFallsBackToNativeImageMarkerWhenPropertyAbsent() {
        assertThat(provider(new Properties(), Map.of(),
                Map.of("org.graalvm.nativeimage.imagecode", "runtime"), List.of())
                .get().build().type()).isEqualTo("native");
    }

    @Test
    void buildTypePropertyWinsOverNativeImageMarker() {
        Properties props = new Properties();
        props.setProperty("type", "jvm");

        assertThat(provider(props, Map.of(),
                Map.of("org.graalvm.nativeimage.imagecode", "runtime"), List.of())
                .get().build().type()).isEqualTo("jvm");
    }

    @Test
    void malformedDirtyPropertyIsNull() {
        Properties props = new Properties();
        props.setProperty("dirty", "maybe");

        assertThat(provider(props, Map.of(), Map.of(), List.of()).get().dirty()).isNull();
    }

    @Test
    void baseImagesComeFromEnv() {
        Map<String, String> env = Map.of(
                "NANOFAAS_BUILD_BASE_IMAGE", "eclipse-temurin:25-jdk",
                "NANOFAAS_RUNTIME_BASE_IMAGE", "gcr.io/distroless/base-debian13:nonroot");

        BuildMetadata metadata = provider(new Properties(), env, Map.of(), List.of()).get();

        assertThat(metadata.build().baseImages().builder()).isEqualTo("eclipse-temurin:25-jdk");
        assertThat(metadata.build().baseImages().runtime()).isEqualTo("gcr.io/distroless/base-debian13:nonroot");
    }

    @Test
    void readsVersionRevisionDirtyTypeVariantOptimizationFromProperties() {
        Properties props = new Properties();
        props.setProperty("version", "0.4.0");
        props.setProperty("revision", "a".repeat(40));
        props.setProperty("dirty", "true");
        props.setProperty("type", "jvm");
        props.setProperty("variant", "jvm-g1-c2");
        props.setProperty("optimization", "c2");

        BuildMetadata metadata = provider(props, Map.of(), Map.of(), List.of()).get();

        assertThat(metadata.version()).isEqualTo("0.4.0");
        assertThat(metadata.revision()).isEqualTo("a".repeat(40));
        assertThat(metadata.dirty()).isTrue();
        assertThat(metadata.build().type()).isEqualTo("jvm");
        assertThat(metadata.build().variant()).isEqualTo("jvm-g1-c2");
        assertThat(metadata.build().optimization()).isEqualTo("c2");
    }

    @Test
    void getReturnsSameImmutableInstanceEveryCall() {
        BuildMetadataProvider provider = provider(new Properties(), Map.of(), Map.of(), List.of());

        assertThat(provider.get()).isSameAs(provider.get());
    }

    @Test
    void productionConstructorLoadsGeneratedBuildPropertiesResource() {
        // META-INF/nanofaas-build.properties is generated by the build-metadata module's
        // generateBuildMetadata Gradle task and is on this test classpath; the no-arg
        // constructor must not throw, and the generated fields must come back populated.
        BuildMetadata metadata = new BuildMetadataProvider().get();

        assertThat(metadata.version()).isNotBlank();
        assertThat(metadata.build().type()).isEqualTo("jvm");
    }

    @Test
    void productionConstructorToleratesMissingBuildPropertiesResource() {
        // A classloader with no delegate parent and no classpath entries can't see
        // META-INF/nanofaas-build.properties regardless of whether generateBuildMetadata
        // ran - this exercises loadBuildProperties()'s "in == null" branch, which a jar
        // assembled without that Gradle task hitting at runtime must also tolerate.
        ClassLoader emptyClassLoader = new URLClassLoader(new URL[0], null);

        BuildMetadata metadata = new BuildMetadataProvider(emptyClassLoader).get();

        assertThat(metadata.version()).isNull();
        assertThat(metadata.revision()).isNull();
        assertThat(metadata.dirty()).isNull();
        assertThat(metadata.modules()).isNull();
        assertThat(metadata.build().variant()).isNull();
        assertThat(metadata.build().optimization()).isNull();
    }
}
