package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import java.util.List;

interface CliCommandExecutor {
    ExecutionResult run(List<String> command);
}
