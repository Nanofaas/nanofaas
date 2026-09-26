package it.unimib.datai.nanofaas.gradle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecipeReaderTest {

    private static final String VALID = """
            schemaVersion: 1
            name: demo
            registry: {repository: ghcr.io/my-org, tag: "1.0.0"}
            controlPlane:
              modules: [async-queue]
              build: {mode: jvm}
              jvm: {args: ['-Xmx128m', '-Dgreeting=hello world', '-Dquote="x"']}
              container: {image: control-plane}
              config:
                nanofaas: {metrics: {profile: basic}}
            functions:
              - name: word-stats
                sdk: java-lite
                build: {mode: native}
                container: {image: word-stats-java-lite}
              - name: word-stats
                sdk: python
                container: {image: word-stats-python}
            """;

    @TempDir
    Path tempDir;

    @Test
    void rejectsJvmOptionsForNative() throws IOException {
        Path file = tempDir.resolve("recipe.yaml");
        Files.writeString(file, """
                schemaVersion: 1
                name: demo
                controlPlane:
                  modules: []
                  build: {mode: native}
                  jvm: {args: ['-Xmx128m']}
                """);
        assertThatThrownBy(() -> new RecipeReader().read(file, null))
                .isInstanceOf(GradleException.class).hasMessageContaining("jvm");
    }

    @Test
    void readsValidRecipeWithHashAndTag() throws Exception {
        Path file = write(VALID);

        RecipeReader.Document document = new RecipeReader().read(file, null);

        assertThat(document.source()).isEqualTo(file);
        assertThat(document.effectiveTag()).isEqualTo("1.0.0");
        assertThat(document.sourceSha256()).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(VALID.getBytes(StandardCharsets.UTF_8))));
        assertThat(document.data().at("/controlPlane/jvm/args/1").asText()).isEqualTo("-Dgreeting=hello world");
        assertThat(document.data().at("/controlPlane/jvm/args/2").asText()).isEqualTo("-Dquote=\"x\"");
        assertThat(document.data().at("/functions/1/sdk").asText()).isEqualTo("python");
    }

    @Test
    void tagOverrideReplacesOnlyTheEffectiveTag() throws IOException {
        Path file = write(VALID);

        RecipeReader.Document document = new RecipeReader().read(file, "1.0.1");

        assertThat(document.effectiveTag()).isEqualTo("1.0.1");
        assertThat(document.data().at("/registry/tag").asText()).isEqualTo("1.0.0");
        assertThat(Files.readString(file)).isEqualTo(VALID);
    }

    @Test
    void usesLocalTagWithoutRegistry() throws IOException {
        Path file = write("""
                schemaVersion: 1
                name: demo
                controlPlane: {modules: [], build: {mode: jvm}}
                """);

        RecipeReader.Document document = new RecipeReader().read(file, null);

        assertThat(document.effectiveTag()).isEqualTo("local");
        assertThat(document.data().path("functions").isMissingNode()).isTrue();
    }

    @Test
    void rejectsTagOverrideWithoutRegistry() throws IOException {
        Path file = write("""
                schemaVersion: 1
                name: demo
                controlPlane: {modules: [], build: {mode: jvm}}
                """);

        assertThatThrownBy(() -> new RecipeReader().read(file, "1.0.1"))
                .isInstanceOf(GradleException.class).hasMessageContaining("recipeTag").hasMessageContaining("registry");
    }

    @Test
    void rejectsInvalidTagOverride() throws IOException {
        Path file = write(VALID);

        assertThatThrownBy(() -> new RecipeReader().read(file, "bad tag"))
                .isInstanceOf(GradleException.class).hasMessageContaining("recipeTag");
        assertThatThrownBy(() -> new RecipeReader().read(file, "1.0.1\n"))
                .isInstanceOf(GradleException.class).hasMessageContaining("recipeTag");
    }

    @Test
    void schemaConformsToBundledDraft202012Metaschema() throws IOException {
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        Schema metaschema = registry.getSchema(SchemaLocation.of(SpecificationVersion.DRAFT_2020_12.getDialectId()));
        try (var schema = getClass().getClassLoader().getResourceAsStream(RecipeReader.SCHEMA_RESOURCE)) {
            assertThat(metaschema.validate(new ObjectMapper().readTree(schema))).isEmpty();
        }
    }

    @Test
    void errorsNameFileAndFieldPath() throws IOException {
        Path file = write(VALID.replace("sdk: python", "sdk: ruby"));

        assertThatThrownBy(() -> new RecipeReader().read(file, null))
                .isInstanceOf(GradleException.class)
                .hasMessageContaining(file.toString())
                .hasMessageContaining("functions[1].sdk");
    }

    private static final String BASE = "schemaVersion: 1\nname: demo\n";
    private static final String CP = "controlPlane: {modules: [], build: {mode: jvm}}\n";

    static Stream<Arguments> invalidRecipes() {
        return Stream.of(
                Arguments.of("duplicate key", BASE + "name: other\n" + CP, "name"),
                Arguments.of("unsupported version", "schemaVersion: 3\nname: demo\n" + CP, "supported versions are 1 and 2"),
                Arguments.of("unknown field", BASE + "nmae: x\n" + CP, "nmae"),
                Arguments.of("missing name", "schemaVersion: 1\n" + CP, "name"),
                Arguments.of("missing controlPlane", BASE, "controlPlane"),
                Arguments.of("missing modules", BASE + "controlPlane: {build: {mode: jvm}}\n", "modules"),
                Arguments.of("control plane without mode", BASE + "controlPlane: {modules: []}\n", "build"),
                Arguments.of("bad recipe name", "schemaVersion: 1\nname: Demo_1\n" + CP, "name"),
                Arguments.of("non-string key", BASE + "1: x\n" + CP, "key"),
                Arguments.of("timestamp value", BASE
                        + "controlPlane: {modules: [], build: {mode: jvm}, config: {at: 2024-01-01}}\n", "JSON"),
                Arguments.of("application tag", "schemaVersion: 1\nname: !!binary aGVsbG8=\n" + CP, "JSON"),
                Arguments.of("multiple documents", BASE + CP + "---\nname: x\n", "document"),
                Arguments.of("not a mapping", "[1, 2]\n", "object"),
                Arguments.of("empty file", "", "empty"),
                Arguments.of("python with build", BASE + CP
                        + "functions: [{name: f, sdk: python, build: {mode: jvm}, container: {image: f}}]\n", "build"),
                Arguments.of("go without container", BASE + CP + "functions: [{name: f, sdk: go}]\n", "container"),
                Arguments.of("java function without mode", BASE + CP + "functions: [{name: f, sdk: java}]\n", "build"),
                Arguments.of("jvm on python", BASE + CP
                        + "functions: [{name: f, sdk: python, jvm: {args: [-Xmx1m]}, container: {image: f}}]\n", "jvm"),
                Arguments.of("base image override", BASE
                        + "controlPlane: {modules: [], build: {mode: jvm}, container: {image: cp, baseImage: x}}\n",
                        "baseImage"),
                Arguments.of("container without image", BASE
                        + "controlPlane: {modules: [], build: {mode: jvm}, container: {}}\n", "image"),
                Arguments.of("image with registry", BASE
                        + "controlPlane: {modules: [], build: {mode: jvm}, container: {image: ghcr.io/x}}\n", "image"),
                Arguments.of("registry with scheme", BASE
                        + "registry: {repository: 'https://ghcr.io/x', tag: t}\n" + CP, "repository"),
                Arguments.of("registry without tag", BASE + "registry: {repository: ghcr.io/x}\n" + CP, "tag"),
                Arguments.of("bad tag", BASE + "registry: {repository: ghcr.io/x, tag: '-bad'}\n" + CP, "tag"),
                Arguments.of("newline in jvm arg", BASE
                        + "controlPlane: {modules: [], build: {mode: jvm}, jvm: {args: [\"-Da=1\\nb\"]}}\n", "args"),
                Arguments.of("NUL in jvm arg", BASE
                        + "controlPlane: {modules: [], build: {mode: jvm}, jvm: {args: [\"-Da=\\0\"]}}\n", "args"),
                Arguments.of("empty module name", BASE + "controlPlane: {modules: [''], build: {mode: jvm}}\n", "modules"),
                Arguments.of("config not a mapping", BASE
                        + "controlPlane: {modules: [], build: {mode: jvm}, config: [1]}\n", "config"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRecipes")
    void rejectsInvalidRecipes(String description, String yaml, String expected) throws IOException {
        Path file = write(yaml);

        assertThatThrownBy(() -> new RecipeReader().read(file, null))
                .as(description)
                .isInstanceOf(GradleException.class)
                .hasMessageContaining(file.toString())
                .hasMessageContaining(expected);
    }

    @Test
    void rejectsAliasCycles() throws IOException {
        Path file = write("""
                schemaVersion: 1
                name: demo
                controlPlane: {modules: [], build: {mode: jvm}, config: &loop {self: *loop}}
                """);

        assertThatThrownBy(() -> new RecipeReader().read(file, null))
                .isInstanceOf(GradleException.class).hasMessageContaining(file.toString());
    }

    @Test
    void rejectsMissingFile() {
        Path file = tempDir.resolve("absent.yaml");

        assertThatThrownBy(() -> new RecipeReader().read(file, null))
                .isInstanceOf(GradleException.class).hasMessageContaining(file.toString());
    }

    private static final String V2 = """
            schemaVersion: 2
            name: demo
            controlPlane:
              modules: [build-metadata]
              build:
                mode: native
                variant: native-o3-g1
                native: {optimization: "s", gc: G1, monitoring: [jvmstat]}
            functions:
              - {name: word-stats, sdk: bash, container: {image: ws-bash}}
            services:
              - {name: warm-echo, sdk: java, build: {mode: native, native: {optimization: "3"}}, container: {image: echo}}
              - {name: watchdog, sdk: dockerfile, container: {image: watchdog}}
            """;

    @Test
    void readsV2Recipe() throws IOException {
        RecipeReader.Document document = new RecipeReader().read(write(V2), null);

        assertThat(document.declaredVersion()).isEqualTo(2);
        assertThat(document.data().at("/controlPlane/build/native/gc").asText()).isEqualTo("G1");
        assertThat(document.data().at("/services/1/sdk").asText()).isEqualTo("dockerfile");
    }

    @Test
    void normalisesV1ToTheV2Model() throws IOException {
        RecipeReader.Document document = new RecipeReader().read(write(VALID), null);

        assertThat(document.declaredVersion()).isEqualTo(1);
        assertThat(document.data().get("schemaVersion").asInt()).isEqualTo(2);
        assertThat(document.data().path("services").isMissingNode()).isTrue();
        assertThat(document.data().at("/functions/1/sdk").asText()).isEqualTo("python");
    }

    @ParameterizedTest
    @ValueSource(strings = {"3", "3.0", "3e0"})
    void normalisesNumericOptimizationOnEveryJavaComponent(String value) throws IOException {
        Path file = write("""
                schemaVersion: 2
                name: demo
                controlPlane: {modules: [build-metadata], build: {mode: native, native: {optimization: %s}}}
                functions: [{name: word-stats, sdk: java, build: {mode: native, native: {optimization: %s}}}]
                services: [{name: warm-echo, sdk: java, build: {mode: native, native: {optimization: %s}}}]
                """.formatted(value, value, value));
        JsonNode data = new RecipeReader().read(file, null).data();

        for (String pointer : List.of("/controlPlane", "/functions/0", "/services/0")) {
            JsonNode optimization = data.at(pointer + "/build/native/optimization");
            assertThat(optimization.isTextual()).isTrue();
            assertThat(optimization.asText()).isEqualTo("3");
        }
    }

    static Stream<Arguments> invalidV2Recipes() {
        String head = "schemaVersion: 2\nname: demo\n";
        String cp = "controlPlane: {modules: [], build: {mode: jvm}}\n";
        return Stream.of(
                Arguments.of("v1 with a v2 field", BASE
                        + "controlPlane: {modules: [], build: {mode: jvm, variant: x}}\n", "variant"),
                Arguments.of("v1 with services", BASE + CP
                        + "services: [{name: watchdog, sdk: dockerfile, container: {image: w}}]\n", "services"),
                Arguments.of("native options in jvm mode", head
                        + "controlPlane: {modules: [], build: {mode: jvm, native: {gc: G1}}}\n", "native"),
                Arguments.of("unknown optimization", head
                        + "controlPlane: {modules: [], build: {mode: native, native: {optimization: fast}}}\n", "optimization"),
                Arguments.of("unknown collector", head
                        + "controlPlane: {modules: [], build: {mode: native, native: {gc: parallel}}}\n", "gc"),
                Arguments.of("unknown monitoring", head
                        + "controlPlane: {modules: [], build: {mode: native, native: {monitoring: [perf]}}}\n", "monitoring"),
                Arguments.of("variant on a function", head + cp
                        + "functions: [{name: f, sdk: java, build: {mode: jvm, variant: x}}]\n", "variant"),
                Arguments.of("bad variant", head
                        + "controlPlane: {modules: [], build: {mode: jvm, variant: Bad_1}}\n", "variant"),
                Arguments.of("bash with build", head + cp
                        + "functions: [{name: f, sdk: bash, build: {mode: jvm}, container: {image: f}}]\n", "build"),
                Arguments.of("dockerfile service without container", head + cp
                        + "services: [{name: w, sdk: dockerfile}]\n", "container"),
                Arguments.of("dockerfile service with jvm", head + cp
                        + "services: [{name: w, sdk: dockerfile, jvm: {args: [-Xmx1m]}, container: {image: w}}]\n", "jvm"),
                Arguments.of("java service without build", head + cp
                        + "services: [{name: warm-echo, sdk: java}]\n", "build"),
                Arguments.of("unknown service sdk", head + cp
                        + "services: [{name: w, sdk: rust, container: {image: w}}]\n", "sdk"),
                Arguments.of("non-integral optimization", head
                        + "controlPlane: {modules: [], build: {mode: native, native: {optimization: 3.5}}}\n", "optimization"),
                Arguments.of("builder in jvm mode", head
                        + "controlPlane: {modules: [], build: {mode: jvm, builder: container}}\n", "builder"),
                Arguments.of("unknown builder", head
                        + "controlPlane: {modules: [], build: {mode: native, builder: cloud}}\n", "builder"),
                Arguments.of("string version", "schemaVersion: '2'\nname: demo\n" + cp, "supported versions are 1 and 2"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidV2Recipes")
    void rejectsInvalidV2Recipes(String description, String yaml, String expected) throws IOException {
        Path file = write(yaml);

        assertThatThrownBy(() -> new RecipeReader().read(file, null))
                .as(description).isInstanceOf(GradleException.class)
                .hasMessageContaining(file.toString()).hasMessageContaining(expected);
    }

    @Test
    void v2SchemaConformsToBundledDraft202012Metaschema() throws IOException {
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        Schema metaschema = registry.getSchema(SchemaLocation.of(SpecificationVersion.DRAFT_2020_12.getDialectId()));
        try (var schema = getClass().getClassLoader().getResourceAsStream(RecipeReader.SCHEMA_V2_RESOURCE)) {
            assertThat(metaschema.validate(new ObjectMapper().readTree(schema))).isEmpty();
        }
    }

    @Test
    void acceptsTheContainerBuilderOnNativeComponents() throws IOException {
        Path file = write("""
                schemaVersion: 2
                name: demo
                controlPlane: {modules: [], build: {mode: native, builder: container}}
                functions: [{name: word-stats, sdk: java, build: {mode: native, builder: host}}]
                services: [{name: warm-echo, sdk: java, build: {mode: native, builder: container}}]
                """);

        JsonNode data = new RecipeReader().read(file, null).data();

        assertThat(data.at("/controlPlane/build/builder").asText()).isEqualTo("container");
        assertThat(data.at("/services/0/build/builder").asText()).isEqualTo("container");
    }

    private Path write(String content) throws IOException {
        Path file = tempDir.resolve("recipe.yaml");
        Files.writeString(file, content);
        return file;
    }
}
