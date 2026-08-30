package it.unimib.datai.nanofaas.modules.buildmetadata;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BuildMetadataController {

    private final BuildMetadataProvider buildMetadataProvider;

    BuildMetadataController(BuildMetadataProvider buildMetadataProvider) {
        this.buildMetadataProvider = buildMetadataProvider;
    }

    @GetMapping("/modules/build-metadata")
    BuildMetadata describe() {
        return buildMetadataProvider.get();
    }
}
