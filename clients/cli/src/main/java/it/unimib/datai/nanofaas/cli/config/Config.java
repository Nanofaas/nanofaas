package it.unimib.datai.nanofaas.cli.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashMap;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class Config {
    private String currentContext;
    private Map<String, Context> contexts = new LinkedHashMap<>();

    public String getCurrentContext() {
        return currentContext;
    }

    public void setCurrentContext(String currentContext) {
        this.currentContext = currentContext;
    }

    public Map<String, Context> getContexts() {
        return contexts;
    }

    public void setContexts(Map<String, Context> contexts) {
        this.contexts = (contexts == null) ? new LinkedHashMap<>() : new LinkedHashMap<>(contexts);
    }
}
