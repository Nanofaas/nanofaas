package it.unimib.datai.nanofaas.cli.commands;

import it.unimib.datai.nanofaas.cli.config.Config;
import it.unimib.datai.nanofaas.cli.config.ConfigStore;
import it.unimib.datai.nanofaas.cli.config.Context;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RootCommandTest {

    @TempDir
    Path tmp;

    @Test
    void exposesOnlyBackendNeutralCommands() {
        CommandLine cli = new CommandLine(new RootCommand());

        assertThat(cli.getSubcommands().keySet())
                .isEqualTo(Set.of("fn", "invoke", "enqueue", "exec", "deploy", "control-plane"));
    }

    @Test
    void helpPrintsUsage() {
        RootCommand cmd = new RootCommand();
        CommandLine cli = new CommandLine(cmd);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        cli.setOut(new PrintWriter(out, true));

        int exit = cli.execute("--help");

        assertThat(exit).isZero();
        assertThat(out.toString()).contains("Usage:");
    }

    @Test
    void leafHelpPrintsUsage() {
        CommandLine cli = new CommandLine(new RootCommand());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        cli.setOut(new PrintWriter(out, true));

        int exit = cli.execute("fn", "list", "--help");

        assertThat(exit).isZero();
        assertThat(out.toString()).contains("Usage: nanofaas fn list");
    }

    @Test
    void newLeafCommandsPrintUsage() {
        assertLeafUsage("Usage: nanofaas fn replicas", "fn", "replicas");
        assertLeafUsage("Usage: nanofaas fn update", "fn", "update");
        assertLeafUsage("Usage: nanofaas control-plane info", "control-plane", "info");
        assertLeafUsage("Usage: nanofaas control-plane config patch", "control-plane", "config", "patch");
    }

    private static void assertLeafUsage(String expected, String... path) {
        CommandLine cli = new CommandLine(new RootCommand());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        cli.setOut(new PrintWriter(out, true));

        String[] args = new String[path.length + 1];
        System.arraycopy(path, 0, args, 0, path.length);
        args[path.length] = "--help";

        int exit = cli.execute(args);

        assertThat(exit).isZero();
        assertThat(out.toString()).contains(expected);
    }

    @Test
    void globalOptionIsAcceptedAfterSubcommands() {
        RootCommand command = new RootCommand();
        CommandLine cli = new CommandLine(command);

        cli.parseArgs("fn", "list", "--endpoint", "http://localhost:8080");

        assertThat(command.resolvedContext().endpoint()).isEqualTo("http://localhost:8080");
    }

    @Test
    void versionComesFromTheBuild() {
        CommandLine cli = new CommandLine(new RootCommand());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        cli.setOut(new PrintWriter(out, true));

        int exit = cli.execute("--version");

        assertThat(exit).isZero();
            assertThat(out.toString()).contains("nanofaas 0.21.0");
    }

    @Test
    void commandWithNoEndpointExitsNonZero() {
        RootCommand cmd = new RootCommand();
        CommandLine cli = new CommandLine(cmd);

        int exit = cli.execute("fn", "list");
        assertThat(exit).isNotZero();
    }

    @Test
    void namespaceOptionIsNotAccepted() {
        CommandLine cli = new CommandLine(new RootCommand());

        assertThatThrownBy(() -> cli.parseArgs("--namespace", "unused", "fn", "list"))
                .isInstanceOf(CommandLine.ParameterException.class);
    }

    @Test
    void configOptionLoadsFromCustomPath() {
        Path cfgPath = tmp.resolve("custom-config.yaml");
        ConfigStore store = new ConfigStore(cfgPath, k -> null);
        Config cfg = new Config();
        cfg.setCurrentContext("prod");
        Context ctx = new Context();
        ctx.setEndpoint("http://prod:8080");
        cfg.setContexts(Map.of("prod", ctx));
        store.save(cfg);

        RootCommand cmd = new RootCommand();
        CommandLine cli = new CommandLine(cmd);

        cli.parseArgs("--config", cfgPath.toString(), "fn", "list");

        assertThat(cmd.resolvedContext().endpoint()).isEqualTo("http://prod:8080");
    }

    @Test
    void endpointOptionOverridesConfig() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("[]"));

            RootCommand cmd = new RootCommand();
            CommandLine cli = new CommandLine(cmd);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PrintStream prev = System.out;
            System.setOut(new PrintStream(out));
            try {
                int exit = cli.execute("--endpoint", server.url("/").toString(), "fn", "list");
                assertThat(exit).isZero();
            } finally {
                System.setOut(prev);
            }
        }
    }
}
