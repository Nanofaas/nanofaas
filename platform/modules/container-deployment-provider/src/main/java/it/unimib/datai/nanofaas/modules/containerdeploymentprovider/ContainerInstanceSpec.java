package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import java.util.List;
import java.util.Map;

record ContainerInstanceSpec(
        String containerName,
        String image,
        int hostPort,
        List<String> command,
        Map<String, String> env
) {
}
