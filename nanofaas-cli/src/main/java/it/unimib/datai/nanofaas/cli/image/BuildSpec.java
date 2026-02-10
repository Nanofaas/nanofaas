package it.unimib.datai.nanofaas.cli.image;

import java.nio.file.Path;
import java.util.Map;

public record BuildSpec(
        Path context,
        Path dockerfile,
        String platform,
        boolean push,
        Map<String, String> buildArgs
) {
}
