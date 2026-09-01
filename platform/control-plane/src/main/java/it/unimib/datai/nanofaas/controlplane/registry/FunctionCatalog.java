package it.unimib.datai.nanofaas.controlplane.registry;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class FunctionCatalog {
    private static final int SCHEMA = 1;
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path path;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final MoveOperation moveOperation;

    @Autowired
    public FunctionCatalog(FunctionCatalogProperties properties, ObjectMapper objectMapper, Validator validator) {
        this(properties, objectMapper, validator,
                (source, target) -> Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
    }

    FunctionCatalog(FunctionCatalogProperties properties, ObjectMapper objectMapper, Validator validator,
                    MoveOperation moveOperation) {
        this.path = properties.path();
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.moveOperation = moveOperation;
    }

    public List<RegisteredFunction> load() {
        if (Files.notExists(path)) {
            return List.of();
        }
        try {
            FunctionCatalogSnapshot snapshot = objectMapper.readValue(Files.readAllBytes(path), FunctionCatalogSnapshot.class);
            if (snapshot.schemaVersion() != SCHEMA) {
                throw new IllegalStateException("Unsupported function catalog schema: " + snapshot.schemaVersion());
            }
            return validatedSorted(snapshot.functions());
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Cannot load function catalog: " + path, exception);
        }
    }

    public void save(Collection<RegisteredFunction> functions) {
        List<RegisteredFunction> sorted = validatedSorted(functions);
        Path parent = path.toAbsolutePath().getParent();
        Path temporary = null;
        try {
            Files.createDirectories(parent);
            setPermissions(parent, DIRECTORY_PERMISSIONS);
            temporary = Files.createTempFile(parent, ".functions-", ".json");
            setPermissions(temporary, FILE_PERMISSIONS);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer content = ByteBuffer.wrap(objectMapper.writeValueAsBytes(new FunctionCatalogSnapshot(SCHEMA, sorted)));
                while (content.hasRemaining()) {
                    channel.write(content);
                }
                channel.force(true);
            }
            moveOperation.move(temporary, path);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Cannot save function catalog: " + path, exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The original catalog is retained when a move fails.
                }
            }
        }
    }

    private List<RegisteredFunction> validatedSorted(Collection<RegisteredFunction> functions) {
        try {
            List<RegisteredFunction> sorted = functions.stream().sorted(Comparator.comparing(RegisteredFunction::name)).toList();
            if (sorted.stream().map(RegisteredFunction::name).distinct().count() != sorted.size()) {
                throw new IllegalStateException("Function catalog contains duplicate names");
            }
            for (RegisteredFunction function : sorted) {
                Set<ConstraintViolation<Object>> violations = validator.validate(function.spec());
                if (!violations.isEmpty()) {
                    throw new IllegalStateException("Function catalog contains an invalid function: " + function.name());
                }
            }
            return sorted;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Function catalog contains an invalid function", exception);
        }
    }

    private static void setPermissions(Path file, Set<PosixFilePermission> permissions) throws IOException {
        if (Files.getFileAttributeView(file, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(file, permissions);
        }
    }
}
