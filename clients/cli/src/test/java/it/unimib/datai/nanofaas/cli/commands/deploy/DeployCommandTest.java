package it.unimib.datai.nanofaas.cli.commands.deploy;

import it.unimib.datai.nanofaas.cli.commands.RootCommand;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DeployCommandTest {

    private MockWebServer server;

    @TempDir
    Path tmp;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void deployWithMissingImageExitsNonZero() throws Exception {
        Path fn = tmp.resolve("function.yaml");
        Files.writeString(fn, """
                name: echo
                x-cli:
                  build:
                    context: .
                """);

        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);

        int exit = cli.execute(
                "--endpoint", server.url("/").toString(),
                "deploy", "-f", fn.toString()
        );
        assertThat(exit).isNotEqualTo(0);
    }

    @Test
    void deployWithBlankImageExitsNonZero() throws Exception {
        Path fn = tmp.resolve("function.yaml");
        Files.writeString(fn, """
                name: echo
                image: "  "
                x-cli:
                  build:
                    context: .
                """);

        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);

        int exit = cli.execute(
                "--endpoint", server.url("/").toString(),
                "deploy", "-f", fn.toString()
        );
        assertThat(exit).isNotEqualTo(0);
    }

    @Test
    void deployWithMissingBuildSpecExitsNonZero() throws Exception {
        Path fn = tmp.resolve("function.yaml");
        Files.writeString(fn, """
                name: echo
                image: registry.example/echo:1
                """);

        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);

        int exit = cli.execute(
                "--endpoint", server.url("/").toString(),
                "deploy", "-f", fn.toString()
        );
        assertThat(exit).isNotEqualTo(0);
    }

    @Test
    void deployWithMissingFileExitsNonZero() {
        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);

        int exit = cli.execute(
                "--endpoint", server.url("/").toString(),
                "deploy", "-f", tmp.resolve("nonexistent.yaml").toString()
        );
        assertThat(exit).isNotEqualTo(0);
    }

    @Test
    void deployDockerBuildFailsExitsNonZero() throws Exception {
        // Create function YAML with valid image and build spec pointing to empty context
        Path ctx = tmp.resolve("ctx");
        Files.createDirectories(ctx);
        // No Dockerfile → docker buildx will fail

        Path fn = tmp.resolve("function.yaml");
        Files.writeString(fn, """
                name: echo
                image: nanofaas-test/echo:test
                x-cli:
                  build:
                    context: %s
                    push: false
                """.formatted(ctx));

        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);

        int exit = cli.execute(
                "--endpoint", server.url("/").toString(),
                "deploy", "-f", fn.toString()
        );
        // docker buildx fails → exit non-zero
        assertThat(exit).isNotEqualTo(0);
    }

    @Test
    void deploySuccessRegistersFunction() throws Exception {
        // Create minimal Dockerfile that builds instantly
        Path ctx = tmp.resolve("build-ctx");
        Files.createDirectories(ctx);
        Files.writeString(ctx.resolve("Dockerfile"), "FROM scratch\n");

        Path fn = tmp.resolve("function.yaml");
        Files.writeString(fn, """
                name: echo
                image: nanofaas-test-deploy:latest
                x-cli:
                  build:
                    context: %s
                    dockerfile: %s/Dockerfile
                    push: false
                """.formatted(ctx, ctx));

        // register -> 201 created
        server.enqueue(new MockResponse()
                .setResponseCode(201)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"name\":\"echo\",\"image\":\"nanofaas-test-deploy:latest\"}"));

        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);

        int exit = cli.execute(
                "--endpoint", server.url("/").toString(),
                "deploy", "-f", fn.toString()
        );
        assertThat(exit).isEqualTo(0);

        RecordedRequest req = server.takeRequest();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/v1/functions");
        assertThat(req.getBody().readUtf8()).contains("\"name\":\"echo\"");
    }

    @Test
    void deployOn409ConflictReplacesWhenDifferent() throws Exception {
        Path ctx = tmp.resolve("build-ctx2");
        Files.createDirectories(ctx);
        Files.writeString(ctx.resolve("Dockerfile"), "FROM scratch\n");

        Path fn = tmp.resolve("function.yaml");
        Files.writeString(fn, """
                name: echo
                image: nanofaas-test-deploy2:latest
                x-cli:
                  build:
                    context: %s
                    dockerfile: %s/Dockerfile
                    push: false
                """.formatted(ctx, ctx));

        // 1) register → 409 conflict
        server.enqueue(new MockResponse().setResponseCode(409));
        // 2) GET → different spec
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"name\":\"echo\",\"image\":\"nanofaas-test-deploy2:OLD\"}"));
        // 3) DELETE → 204
        server.enqueue(new MockResponse().setResponseCode(204));
        // 4) register → 201
        server.enqueue(new MockResponse()
                .setResponseCode(201)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"name\":\"echo\",\"image\":\"nanofaas-test-deploy2:latest\"}"));

        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);

        int exit = cli.execute(
                "--endpoint", server.url("/").toString(),
                "deploy", "-f", fn.toString()
        );
        assertThat(exit).isEqualTo(0);

        assertThat(server.getRequestCount()).isEqualTo(4);
        RecordedRequest r1 = server.takeRequest();
        assertThat(r1.getMethod()).isEqualTo("POST");
        RecordedRequest r2 = server.takeRequest();
        assertThat(r2.getMethod()).isEqualTo("GET");
        RecordedRequest r3 = server.takeRequest();
        assertThat(r3.getMethod()).isEqualTo("DELETE");
        RecordedRequest r4 = server.takeRequest();
        assertThat(r4.getMethod()).isEqualTo("POST");
    }

    @Test
    void deployOn409SameSpecSkipsReplace() throws Exception {
        Path ctx = tmp.resolve("build-ctx3");
        Files.createDirectories(ctx);
        Files.writeString(ctx.resolve("Dockerfile"), "FROM scratch\n");

        Path fn = tmp.resolve("function.yaml");
        Files.writeString(fn, """
                name: echo
                image: nanofaas-test-deploy3:latest
                x-cli:
                  build:
                    context: %s
                    dockerfile: %s/Dockerfile
                    push: false
                """.formatted(ctx, ctx));

        // 1) register → 409
        server.enqueue(new MockResponse().setResponseCode(409));
        // 2) GET → same spec
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"name\":\"echo\",\"image\":\"nanofaas-test-deploy3:latest\"}"));

        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);

        int exit = cli.execute(
                "--endpoint", server.url("/").toString(),
                "deploy", "-f", fn.toString()
        );
        assertThat(exit).isEqualTo(0);

        // Only POST + GET, no DELETE or re-POST
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void deployOn409NullGetRetries() throws Exception {
        Path ctx = tmp.resolve("build-ctx4");
        Files.createDirectories(ctx);
        Files.writeString(ctx.resolve("Dockerfile"), "FROM scratch\n");

        Path fn = tmp.resolve("function.yaml");
        Files.writeString(fn, """
                name: echo
                image: nanofaas-test-deploy4:latest
                x-cli:
                  build:
                    context: %s
                    dockerfile: %s/Dockerfile
                    push: false
                """.formatted(ctx, ctx));

        // 1) register → 409
        server.enqueue(new MockResponse().setResponseCode(409));
        // 2) GET → 404 (null)
        server.enqueue(new MockResponse().setResponseCode(404));
        // 3) retry register → 201
        server.enqueue(new MockResponse()
                .setResponseCode(201)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"name\":\"echo\",\"image\":\"nanofaas-test-deploy4:latest\"}"));

        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);

        int exit = cli.execute(
                "--endpoint", server.url("/").toString(),
                "deploy", "-f", fn.toString()
        );
        assertThat(exit).isEqualTo(0);

        assertThat(server.getRequestCount()).isEqualTo(3);
    }
}
