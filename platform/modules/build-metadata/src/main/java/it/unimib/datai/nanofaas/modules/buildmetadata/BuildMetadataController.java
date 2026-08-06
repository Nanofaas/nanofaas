package it.unimib.datai.nanofaas.modules.buildmetadata;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class BuildMetadataController {

    @GetMapping("/modules/build-metadata")
    Map<String, String> describe() {
        return Map.of(
                "module", "build-metadata",
                "status", "enabled"
        );
    }
}
