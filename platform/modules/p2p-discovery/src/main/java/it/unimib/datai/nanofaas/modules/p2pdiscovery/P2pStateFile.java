package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Loads the YAML file at boot and rewrites only its {@code state} section, atomically. */
public final class P2pStateFile {
    private static final Logger log = LoggerFactory.getLogger(P2pStateFile.class);
    // strict: a misspelled operator key is an error (warned, and the text backed up before any rewrite), not silence
    private static final YAMLMapper YAML = YAMLMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    private final Path path;

    public P2pStateFile(Path path) {
        this.path = path;
    }

    /** Never throws: a missing, empty or corrupt file yields an empty model (and is left untouched). */
    public P2pFile load() {
        try {
            return parse();
        } catch (IOException | RuntimeException e) {
            log.warn("ignoring unreadable p2p state file {}: {}", path, e.toString());
            return new P2pFile(null, null);
        }
    }

    private P2pFile parse() throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) == 0) {
            return new P2pFile(null, null);
        }
        P2pFile f = YAML.readValue(path.toFile(), P2pFile.class);
        return f == null ? new P2pFile(null, null) : f;
    }

    /** The current file, or an empty model after copying an unparsable one aside so its text is not lost. */
    private P2pFile existingOrBackedUp() throws IOException {
        try {
            return parse();
        } catch (IOException | RuntimeException e) {
            Path backup = freeBackupName();
            Files.copy(path, backup);
            log.warn("p2p state file {} is unreadable ({}); original kept as {}", path, e.getMessage(), backup);
            return new P2pFile(null, null);
        }
    }

    /** {@code <file>.corrupt}, then {@code .corrupt.1}, ...: a second corruption never overwrites the first copy. */
    private Path freeBackupName() {
        Path candidate = path.resolveSibling(path.getFileName() + ".corrupt");
        for (int n = 1; Files.exists(candidate); n++) {
            candidate = path.resolveSibling(path.getFileName() + ".corrupt." + n);
        }
        return candidate;
    }

    /** Deletes temporary files a killed process left next to the file; call at startup, before any save. */
    public void removeStaleTempFiles() {
        Path dir = path.toAbsolutePath().getParent();
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> stale = Files.newDirectoryStream(dir, path.getFileName() + "*.tmp")) {
            for (Path p : stale) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            log.debug("could not clean temporary files next to {}: {}", path, e.getMessage());
        }
    }

    /** Keeps the operator's {@code config}, replaces {@code state}; write temp file then atomic move. */
    public synchronized void saveState(P2pFile.State state) {
        try {
            Path dir = path.toAbsolutePath().getParent();
            Files.createDirectories(dir);
            P2pFile out = new P2pFile(existingOrBackedUp().config(), state);
            Path tmp = Files.createTempFile(dir, path.getFileName().toString(), ".tmp");
            try {
                YAML.writeValue(tmp.toFile(), out);
                try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                    ch.force(true);   // data on disk before the rename makes it the file: no empty file after a power cut
                }
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
