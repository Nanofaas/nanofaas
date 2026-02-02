package com.mcfaas.controlplane.registry;

public class FunctionNotFoundException extends RuntimeException {
    public FunctionNotFoundException() {
        super();
    }

    public FunctionNotFoundException(String functionName) {
        super("Function not found: " + functionName);
    }
}
