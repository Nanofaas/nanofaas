package it.unimib.datai.nanofaas.cli.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigStoreTest {

    @TempDir
    Path tmp;

    @Test
    void roundTripYamlConfig() {
        Path p = tmp.resolve("config.yaml");
        ConfigStore store = new ConfigStore(p);

        Config cfg = new Config();
        cfg.setCurrentContext("dev");
        Context ctx = new Context();
        ctx.setEndpoint("http://localhost:8080");
        ctx.setNamespace("nanofaas");
        cfg.setContexts(Map.of("dev", ctx));

        store.save(cfg);
        Config loaded = store.load();

        assertThat(loaded.getCurrentContext()).isEqualTo("dev");
        assertThat(loaded.getContexts()).containsKey("dev");
        assertThat(loaded.getContexts().get("dev").getEndpoint()).isEqualTo("http://localhost:8080");
        assertThat(loaded.getContexts().get("dev").getNamespace()).isEqualTo("nanofaas");
    }

    @Test
    void endpointEnvOverrideWins() {
        Path p = tmp.resolve("config.yaml");
        ConfigStore store = new ConfigStore(p, k -> k.equals("NANOFAAS_ENDPOINT") ? "http://override:8080" : null);

        Config cfg = new Config();
        cfg.setCurrentContext("dev");
        Context ctx = new Context();
        ctx.setEndpoint("http://localhost:8080");
        cfg.setContexts(Map.of("dev", ctx));
        store.save(cfg);

        ResolvedContext resolved = store.loadResolvedContext();

        assertThat(resolved.endpoint()).isEqualTo("http://override:8080");
    }
}
