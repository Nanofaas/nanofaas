package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import it.unimib.datai.nanofaas.controlplane.deployment.ImageInventory;
import it.unimib.datai.nanofaas.controlplane.deployment.ImageInventorySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

final class CliImageInventorySource implements ImageInventorySource {
    private final String executable;
    private final ImageInventoryCommand command;
    CliImageInventorySource(String executable, ImageInventoryCommand command) {
        this.executable = executable;
        this.command = command;
    }

    @Override public ImageInventory snapshot(Duration timeout, int maxEntries) {
        try {
            String output = command.run(List.of(executable, "image", "ls", "--all", "--no-trunc",
                    "--digests", "--format", "{{json .}}"), timeout, 300 * 1024);
            var mapper = JsonMapper.builder().build();
            List<ImageInventory.Entry> entries = new ArrayList<>();
            for (String line : output.lines().filter(s -> !s.isBlank()).toList()) {
                if (entries.size() >= maxEntries) return unavailable("LIMIT_EXCEEDED");
                JsonNode row = mapper.readTree(line);
                String repository = value(row, "Repository");
                String tag = value(row, "Tag");
                String reference = repository == null ? null : repository + (tag == null ? "" : ":" + tag);
                entries.add(new ImageInventory.Entry(null, reference == null ? List.of() : List.of(reference),
                        value(row, "Digest"), value(row, "ID")));
            }
            return new ImageInventory("container-local", "local-engine", Instant.now(),
                    ImageInventory.Status.AVAILABLE, null, entries);
        } catch (RuntimeException e) {
            return unavailable("BACKEND_ERROR");
        }
    }

    private static String value(JsonNode row, String field) {
        String value = row.path(field).asString();
        return value == null || value.isBlank() || "<none>".equals(value) ? null : value;
    }
    private static ImageInventory unavailable(String reason) {
        return new ImageInventory("container-local", "local-engine", Instant.now(),
                ImageInventory.Status.UNAVAILABLE, reason, List.of());
    }
}
