package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import it.unimib.datai.nanofaas.common.model.ResourceSpec;
import java.util.List;
import java.util.Map;

record ContainerInstanceSpec(
        String containerName,
        String image,
        Integer hostPort,
        List<String> command,
        Map<String, String> env,
        ResourceSpec resources
) {
}
