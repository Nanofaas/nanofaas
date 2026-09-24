package it.unimib.datai.nanofaas.cli.commands.fn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unimib.datai.nanofaas.cli.commands.RootCommand;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FnTestCommandTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

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
    void testPayloadDirectoryInvokesWithInputOnlyAndPasses() throws Exception {
        Path payloads = Files.createDirectories(tmp.resolve("payloads"));
        Files.writeString(payloads.resolve("happy-path.json"), """
                {
                  "description": "valid input",
                  "input": {"key": "value"},
                  "expected": {"result": "ok"}
                }
                """);

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"executionId\":\"e1\",\"status\":\"success\",\"output\":{\"result\":\"ok\"}}"));

        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream previousOut = System.out;
        System.setOut(new PrintStream(out));
        try {
            int exit = cli.execute(
                    "--endpoint", server.url("/").toString(),
                    "fn", "test", "echo",
                    "--payloads", payloads.toString()
            );

            assertThat(exit).isZero();
        } finally {
            System.setOut(previousOut);
        }

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/v1/functions/echo:invoke");

        JsonNode body = JSON.readTree(request.getBody().readUtf8());
        assertThat(body.get("input")).isEqualTo(JSON.readTree("{\"key\":\"value\"}"));
        assertThat(body.has("expected")).isFalse();
        assertThat(body.has("description")).isFalse();

        assertThat(out.toString())
                .contains("\u2705")
                .contains("happy-path.json")
                .contains("1 passed, 0 failed");
    }

    @Test
    void testPayloadMismatchReturnsNonZero() throws Exception {
        Path payloads = Files.createDirectories(tmp.resolve("payloads"));
        Files.writeString(payloads.resolve("happy-path.json"), """
                {
                  "description": "valid input",
                  "input": {"key": "value"},
                  "expected": {"result": "ok"}
                }
                """);

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"executionId\":\"e1\",\"status\":\"success\",\"output\":{\"result\":\"wrong\"}}"));

        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream previousOut = System.out;
        System.setOut(new PrintStream(out));
        try {
            int exit = cli.execute(
                    "--endpoint", server.url("/").toString(),
                    "fn", "test", "echo",
                    "--payloads", payloads.toString()
            );

            assertThat(exit).isEqualTo(1);
        } finally {
            System.setOut(previousOut);
        }

        assertThat(out.toString())
                .contains("\u274c")
                .contains("expected")
                .contains("{\"result\":\"ok\"}")
                .contains("actual")
                .contains("{\"result\":\"wrong\"}")
                .contains("0 passed, 1 failed");
    }

    @Test
    void testPayloadFailsWhenInvocationStatusIsNotSuccess() throws Exception {
        Path payloads = Files.createDirectories(tmp.resolve("payloads"));
        Files.writeString(payloads.resolve("happy-path.json"), """
                {
                  "description": "valid input",
                  "input": {"key": "value"},
                  "expected": {"result": "ok"}
                }
                """);

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "executionId": "e1",
                          "status": "error",
                          "output": {"result": "ok"},
                          "error": {"code": "HANDLER_ERROR", "message": "boom"}
                        }
                        """));

        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream previousOut = System.out;
        System.setOut(new PrintStream(out));
        try {
            int exit = cli.execute(
                    "--endpoint", server.url("/").toString(),
                    "fn", "test", "echo",
                    "--payloads", payloads.toString()
            );

            assertThat(exit).isEqualTo(1);
        } finally {
            System.setOut(previousOut);
        }

        assertThat(out.toString())
                .contains("\u274c")
                .contains("status")
                .contains("error")
                .contains("HANDLER_ERROR")
                .contains("0 passed, 1 failed");
    }

    @Test
    void testFunctionDecidedErrorStatusFailsEvenWhenBodyStatusIsSuccess() throws Exception {
        Path payloads = Files.createDirectories(tmp.resolve("payloads"));
        Files.writeString(payloads.resolve("happy-path.json"), """
                {
                  "description": "valid input",
                  "input": {"key": "value"},
                  "expected": {"result": "ok"}
                }
                """);

        server.enqueue(new MockResponse()
                .setResponseCode(404)
                .addHeader("X-NanoFaaS-Function-Status", "true")
                .addHeader("Content-Type", "application/json")
                .setBody("{\"executionId\":\"e1\",\"status\":\"success\",\"output\":{\"result\":\"ok\"}}"));

        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream previousOut = System.out;
        System.setOut(new PrintStream(out));
        try {
            int exit = cli.execute(
                    "--endpoint", server.url("/").toString(),
                    "fn", "test", "echo",
                    "--payloads", payloads.toString()
            );

            assertThat(exit).isEqualTo(1);
        } finally {
            System.setOut(previousOut);
        }

        assertThat(out.toString())
                .contains("❌")
                .contains("0 passed, 1 failed");
    }

    @Test
    void testPayloadPassesWhenFunctionDecidedStatusMatchesExpectedStatusCode() throws Exception {
        Path payloads = Files.createDirectories(tmp.resolve("payloads"));
        Files.writeString(payloads.resolve("missing-input.json"), """
                {
                  "description": "empty input",
                  "input": {},
                  "expected": {"error": "missing"},
                  "expectedStatusCode": 422
                }
                """);
        server.enqueue(functionDecided(422, "{\"error\":\"missing\"}"));

        String out = runFnTest(payloads, 0);

        assertThat(out).contains("✅").contains("1 passed, 0 failed");
    }

    @Test
    void testPayloadFailsWhenStatusDiffersFromExpectedStatusCode() throws Exception {
        Path payloads = Files.createDirectories(tmp.resolve("payloads"));
        Files.writeString(payloads.resolve("missing-input.json"), """
                {
                  "description": "empty input",
                  "input": {},
                  "expected": {"error": "missing"},
                  "expectedStatusCode": 422
                }
                """);
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"executionId\":\"e1\",\"status\":\"success\",\"output\":{\"error\":\"missing\"}}"));

        String out = runFnTest(payloads, 1);

        assertThat(out)
                .contains("expected http status: 422")
                .contains("0 passed, 1 failed");
    }

    private static MockResponse functionDecided(int status, String output) {
        return new MockResponse()
                .setResponseCode(status)
                .addHeader("X-NanoFaaS-Function-Status", "true")
                .addHeader("Content-Type", "application/json")
                .setBody("{\"executionId\":\"e1\",\"status\":\"success\",\"output\":" + output
                        + ",\"statusCode\":" + status + "}");
    }

    private String runFnTest(Path payloads, int expectedExit) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream previousOut = System.out;
        System.setOut(new PrintStream(out));
        try {
            int exit = new CommandLine(new RootCommand()).execute(
                    "--endpoint", server.url("/").toString(),
                    "fn", "test", "echo",
                    "--payloads", payloads.toString());
            assertThat(exit).as(out.toString()).isEqualTo(expectedExit);
        } finally {
            System.setOut(previousOut);
        }
        return out.toString();
    }

    @Test
    void testPayloadResolvesJsonAssetInputRelativeToPayloadFile() throws Exception {
        Path payloads = Files.createDirectories(tmp.resolve("payloads"));
        Path assets = Files.createDirectories(payloads.resolve("assets"));
        Files.writeString(assets.resolve("input.json"), "{\"from\":\"asset\"}");
        Files.writeString(payloads.resolve("asset-input.json"), """
                {
                  "description": "asset input",
                  "input": "@assets/input.json",
                  "expected": {"result": "ok"}
                }
                """);

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"executionId\":\"e1\",\"status\":\"success\",\"output\":{\"result\":\"ok\"}}"));

        RootCommand root = new RootCommand();
        CommandLine cli = new CommandLine(root);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream previousOut = System.out;
        System.setOut(new PrintStream(out));
        try {
            int exit = cli.execute(
                    "--endpoint", server.url("/").toString(),
                    "fn", "test", "echo",
                    "--payloads", payloads.toString()
            );

            assertThat(exit).isZero();
        } finally {
            System.setOut(previousOut);
        }

        RecordedRequest request = server.takeRequest();
        JsonNode body = JSON.readTree(request.getBody().readUtf8());
        assertThat(body.get("input")).isEqualTo(JSON.readTree("{\"from\":\"asset\"}"));
        assertThat(out.toString()).contains("1 passed, 0 failed");
    }
}
