package it.unimib.datai.nanofaas.modules.buildmetadata;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;

@Controller
public class BuildMetadataController {

    @GetMapping("/modules/build-metadata")
    @ResponseBody
    Map<String, String> describe() {
        return Map.of(
                "module", "build-metadata",
                "status", "enabled"
        );
    }
}
