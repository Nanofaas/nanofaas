package it.unimib.datai.nanofaas.modules.buildmetadata;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.function.Supplier;

@RestController
public class BuildMetadataController {

    private final Supplier<BuildMetadata> buildMetadataProvider;

    BuildMetadataController(Supplier<BuildMetadata> buildMetadataProvider) {
        this.buildMetadataProvider = buildMetadataProvider;
    }

    @GetMapping("/modules/build-metadata")
    BuildMetadata describe() {
        return buildMetadataProvider.get();
    }
}
