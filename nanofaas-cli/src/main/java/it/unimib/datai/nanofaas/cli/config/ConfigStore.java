package it.unimib.datai.nanofaas.cli.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

public final class ConfigStore {
    private final Path path;
    private final ObjectMapper yaml;
    private final Function<String, String> getenv;

    public ConfigStore() {
        this(defaultPath(), System::getenv);
    }

    public ConfigStore(Path path) {
        this(path, System::getenv);
    }

    public ConfigStore(Path path, Function<String, String> getenv) {
        this.path = path;
        this.getenv = getenv;
        this.yaml = new ObjectMapper(new YAMLFactory())
                .findAndRegisterModules()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public Config load() {
        if (!Files.exists(path)) {
            return new Config();
        }
        try {
            return yaml.readValue(path.toFile(), Config.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read config: " + path, e);
        }
    }

    public void save(Config config) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            yaml.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), config);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write config: " + path, e);
        }
    }

    public ResolvedContext loadResolvedContext() {
        Config cfg = load();

        String contextName = firstNonBlank(getenv.apply("NANOFAAS_CONTEXT"), cfg.getCurrentContext());
        Context ctx = (contextName == null || cfg.getContexts() == null) ? null : cfg.getContexts().get(contextName);

        String endpoint = firstNonBlank(getenv.apply("NANOFAAS_ENDPOINT"), ctx == null ? null : ctx.getEndpoint());
        String namespace = firstNonBlank(getenv.apply("NANOFAAS_NAMESPACE"), ctx == null ? null : ctx.getNamespace());

        return new ResolvedContext(contextName, endpoint, namespace);
    }

    public Path getPath() {
        return path;
    }

    private static Path defaultPath() {
        String home = System.getProperty("user.home");
        return Path.of(home, ".config", "nanofaas", "config.yaml");
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
