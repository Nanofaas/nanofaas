package it.unimib.datai.nanofaas.cli.image;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class DockerBuildx {
    private DockerBuildx() {}

    public static List<String> toCommand(String image, BuildSpec spec) {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("buildx");
        cmd.add("build");

        if (spec.push()) {
            cmd.add("--push");
        }

        cmd.add("--tag");
        cmd.add(image);

        if (spec.platform() != null && !spec.platform().isBlank()) {
            cmd.add("--platform");
            cmd.add(spec.platform());
        }

        Path dockerfile = spec.dockerfile();
        if (dockerfile != null) {
            cmd.add("-f");
            cmd.add(dockerfile.toString());
        }

        for (Map.Entry<String, String> e : spec.buildArgs().entrySet()) {
            cmd.add("--build-arg");
            cmd.add(e.getKey() + "=" + e.getValue());
        }

        cmd.add(spec.context().toString());
        return cmd;
    }

    public static void run(String image, BuildSpec spec) {
        List<String> cmd = toCommand(image, spec);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.inheritIO();
        try {
            Process p = pb.start();
            int exit = p.waitFor();
            if (exit != 0) {
                throw new IllegalStateException("docker buildx failed with exit code " + exit);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted running docker buildx", e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to run docker buildx", e);
        }
    }
}
