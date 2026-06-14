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

    @Test
    void namespaceEnvOverrideWins() {
        Path p = tmp.resolve("config.yaml");
        ConfigStore store = new ConfigStore(p, k -> k.equals("NANOFAAS_NAMESPACE") ? "override-ns" : null);

        Config cfg = new Config();
        cfg.setCurrentContext("dev");
        Context ctx = new Context();
        ctx.setEndpoint("http://localhost:8080");
        ctx.setNamespace("original-ns");
        cfg.setContexts(Map.of("dev", ctx));
        store.save(cfg);

        ResolvedContext resolved = store.loadResolvedContext();

        assertThat(resolved.namespace()).isEqualTo("override-ns");
        assertThat(resolved.endpoint()).isEqualTo("http://localhost:8080");
    }

    @Test
    void contextEnvOverrideSelectsDifferentContext() {
        Path p = tmp.resolve("config.yaml");
        ConfigStore store = new ConfigStore(p, k -> k.equals("NANOFAAS_CONTEXT") ? "prod" : null);

        Config cfg = new Config();
        cfg.setCurrentContext("dev");

        Context devCtx = new Context();
        devCtx.setEndpoint("http://dev:8080");
        devCtx.setNamespace("dev-ns");

        Context prodCtx = new Context();
        prodCtx.setEndpoint("http://prod:8080");
        prodCtx.setNamespace("prod-ns");

        cfg.setContexts(Map.of("dev", devCtx, "prod", prodCtx));
        store.save(cfg);

        ResolvedContext resolved = store.loadResolvedContext();

        assertThat(resolved.contextName()).isEqualTo("prod");
        assertThat(resolved.endpoint()).isEqualTo("http://prod:8080");
        assertThat(resolved.namespace()).isEqualTo("prod-ns");
    }

    @Test
    void setContextsNullCreatesEmptyMap() {
        Config cfg = new Config();
        cfg.setContexts(null);
        assertThat(cfg.getContexts()).isNotNull().isEmpty();
    }

    @Test
    void configWithNoCurrentContextResolvesNullContextName() {
        Path p = tmp.resolve("config.yaml");
        ConfigStore store = new ConfigStore(p, k -> null);

        Config cfg = new Config();
        // No currentContext set, but contexts map has an entry
        Context ctx = new Context();
        ctx.setEndpoint("http://localhost:8080");
        cfg.setContexts(java.util.Map.of("dev", ctx));
        store.save(cfg);

        ResolvedContext resolved = store.loadResolvedContext();

        // contextName is null since no currentContext and no env override
        assertThat(resolved.contextName()).isNull();
        // endpoint is null since no context was selected
        assertThat(resolved.endpoint()).isNull();
    }

    @Test
    void missingConfigFileReturnsDefaultResolvedContext() {
        Path p = tmp.resolve("nonexistent/config.yaml");
        ConfigStore store = new ConfigStore(p, k -> null);

        ResolvedContext resolved = store.loadResolvedContext();

        assertThat(resolved.contextName()).isNull();
        assertThat(resolved.endpoint()).isNull();
        assertThat(resolved.namespace()).isNull();
    }
}
