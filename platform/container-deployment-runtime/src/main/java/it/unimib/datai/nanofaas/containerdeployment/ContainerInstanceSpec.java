package it.unimib.datai.nanofaas.containerdeployment;

import it.unimib.datai.nanofaas.common.model.ResourceSpec;
import java.util.List;
import java.util.Map;

public record ContainerInstanceSpec(
        String containerName,
        String image,
        List<String> command,
        Map<String, String> env,
        ResourceSpec resources,
        Map<String, String> labels
) {
    public ContainerInstanceSpec {
        labels = labels == null ? Map.of() : Map.copyOf(labels);
    }
}
