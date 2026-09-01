package it.unimib.datai.nanofaas.controlplane.registry;

import java.io.IOException;
import java.nio.file.Path;

@FunctionalInterface
interface MoveOperation {
    void move(Path source, Path target) throws IOException;
}
