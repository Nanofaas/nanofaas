package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import static it.unimib.datai.nanofaas.modules.p2pdiscovery.NodeInformation.*;

/** Bounds JSON application messages independently of Scalecube's transport envelope. */
public final class NodeInformationCodec {
    static final int MAX_BYTES = 1024 * 1024;
    static final int CATEGORY_BYTES = 300 * 1024;
    static final int MAX_ENTRIES = 5000;
    private final JsonMapper mapper = JsonMapper.builder(JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(16384).maxNumberLength(64).build()).build())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public byte[] encode(NodeInformation info) {
        NodeInformation bounded = new NodeInformation(info.schemaVersion(), info.nodeId(), info.sampledAt(),
                bounded(info.functions()), bounded(info.images()), bounded(info.resources()));
        return write(bounded, MAX_BYTES);
    }

    public NodeInformation decode(byte[] bytes, String expectedNodeId) {
        if (bytes == null || bytes.length > MAX_BYTES) throw new IllegalArgumentException("payload limit exceeded");
        try {
            NodeInformation info = mapper.readValue(bytes, NodeInformation.class);
            if (info == null || info.schemaVersion() != 1 || !Objects.equals(info.nodeId(), expectedNodeId)
                    || info.nodeId() == null || info.nodeId().isBlank() || info.sampledAt() == null) {
                throw new IllegalArgumentException("invalid snapshot identity or version");
            }
            validate(info.functions());
            validate(info.images());
            validate(info.resources());
            return info;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid node information", e);
        }
    }

    <T> Category<T> bounded(Category<T> category) {
        try {
            validate(category);
            return category;
        } catch (RuntimeException e) {
            return Category.unavailable("LIMIT_EXCEEDED");
        }
    }

    private void validate(Category<?> category) {
        if (category == null || count(category.data()) > MAX_ENTRIES) {
            throw new IllegalArgumentException("invalid category or entry limit exceeded");
        }
        mapper.readTree(write(category, CATEGORY_BYTES));
    }

    private static int count(Object data) {
        return switch (data) {
            case List<?> list -> list.size();
            case it.unimib.datai.nanofaas.controlplane.deployment.ImageInventory inventory -> inventory.entries().size();
            case ResourceInfo resources -> resources.functions().size();
            case null, default -> 0;
        };
    }

    private byte[] write(Object value, int limit) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStream bounded = new OutputStream() {
            private int written;
            @Override public void write(int b) throws IOException {
                check(1); bytes.write(b);
            }
            @Override public void write(byte[] b, int off, int len) throws IOException {
                check(len); bytes.write(b, off, len);
            }
            private void check(int length) throws IOException {
                if (length > limit - written) throw new IOException("category limit exceeded");
                written += length;
            }
        };
        try {
            mapper.writeValue(bounded, value);
            return bytes.toByteArray();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("cannot encode bounded snapshot", e);
        }
    }
}
