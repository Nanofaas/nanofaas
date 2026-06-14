package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

record ExecutionResult(int exitCode, String output) {

    static ExecutionResult success(String output) {
        return new ExecutionResult(0, output);
    }

    static ExecutionResult failure(int exitCode, String output) {
        return new ExecutionResult(exitCode, output);
    }

    boolean isSuccess() {
        return exitCode == 0;
    }
}
