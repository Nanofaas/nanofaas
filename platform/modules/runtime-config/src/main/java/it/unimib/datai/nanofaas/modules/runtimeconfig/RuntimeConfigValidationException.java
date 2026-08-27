package it.unimib.datai.nanofaas.modules.runtimeconfig;

import java.util.List;

public class RuntimeConfigValidationException extends RuntimeException {
    private final List<String> errors;

    public RuntimeConfigValidationException(List<String> errors) {
        super("Invalid runtime config: " + errors);
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
