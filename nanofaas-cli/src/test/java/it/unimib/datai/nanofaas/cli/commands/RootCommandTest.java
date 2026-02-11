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

import static org.assertj.core.api.Assertions.assertThat;

class RootCommandTest {

    @TempDir
    Path tmp;

    @Test
    void helpPrintsUsage() {
        RootCommand cmd = new RootCommand();
        CommandLine cli = new CommandLine(cmd);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        cli.setOut(new PrintWriter(out, true));

        int exit = cli.execute("--help");

        assertThat(exit).isEqualTo(0);
        assertThat(out.toString()).contains("Usage:");
    }

    @Test
    void commandWithNoEndpointExitsNonZero() {
        RootCommand cmd = new RootCommand();
        CommandLine cli = new CommandLine(cmd);

        int exit = cli.execute("fn", "list");
        assertThat(exit).isNotEqualTo(0);
    }

    @Test
    void namespaceOptionOverridesConfig() throws Exception {
        // Setup config file with namespace
        Path cfgPath = tmp.resolve("config.yaml");
        ConfigStore store = new ConfigStore(cfgPath, k -> null);
        Config cfg = new Config();
        cfg.setCurrentContext("dev");
        Context ctx = new Context();
        ctx.setEndpoint("http://localhost:9999");
        ctx.setNamespace("from-config");
        cfg.setContexts(Map.of("dev", ctx));
        store.save(cfg);

        RootCommand cmd = new RootCommand();
        CommandLine cli = new CommandLine(cmd);

        // Use --config and --namespace to override
        cli.parseArgs("--config", cfgPath.toString(), "--namespace", "from-flag", "fn", "list");

        assertThat(cmd.resolvedContext().namespace()).isEqualTo("from-flag");
    }

    @Test
    void configOptionLoadsFromCustomPath() throws Exception {
        Path cfgPath = tmp.resolve("custom-config.yaml");
        ConfigStore store = new ConfigStore(cfgPath, k -> null);
        Config cfg = new Config();
        cfg.setCurrentContext("prod");
        Context ctx = new Context();
        ctx.setEndpoint("http://prod:8080");
        ctx.setNamespace("prod-ns");
        cfg.setContexts(Map.of("prod", ctx));
        store.save(cfg);

        RootCommand cmd = new RootCommand();
        CommandLine cli = new CommandLine(cmd);

        cli.parseArgs("--config", cfgPath.toString(), "fn", "list");

        assertThat(cmd.resolvedContext().endpoint()).isEqualTo("http://prod:8080");
        assertThat(cmd.resolvedContext().namespace()).isEqualTo("prod-ns");
    }

    @Test
    void endpointOptionOverridesConfig() throws Exception {
        MockWebServer server = new MockWebServer();
        server.start();
        try {
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
                assertThat(exit).isEqualTo(0);
            } finally {
                System.setOut(prev);
            }
        } finally {
            server.shutdown();
        }
    }
}

