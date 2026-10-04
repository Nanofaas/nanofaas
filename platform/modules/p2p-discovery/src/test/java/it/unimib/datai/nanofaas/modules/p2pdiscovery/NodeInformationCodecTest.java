package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import static org.assertj.core.api.Assertions.*;
import static it.unimib.datai.nanofaas.modules.p2pdiscovery.NodeInformation.*;

class NodeInformationCodecTest {
    @Test void boundsCategoryBytesAndRejectsDeepAdditiveFields() {
        var functions = java.util.stream.IntStream.range(0, 100)
                .mapToObj(i -> new FunctionInfo("fn" + i, "LOCAL", "x".repeat(4000), null)).toList();
        assertThat(codec.decode(codec.encode(snapshot(functions)), "a").functions().reasonCode()).isEqualTo("LIMIT_EXCEEDED");
        String json = new String(codec.encode(snapshot(List.of())), StandardCharsets.UTF_8);
        String deep = "{\"future\":" + "[".repeat(18) + "0" + "]".repeat(18) + "," + json.substring(1);
        assertThatThrownBy(() -> codec.decode(deep.getBytes(StandardCharsets.UTF_8), "a"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResourceInfo(Double.NaN, null, null, null, null, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
    private final NodeInformationCodec codec = new NodeInformationCodec();

    static NodeInformation snapshot(List<FunctionInfo> functions) {
        return new NodeInformation(1, "a", Instant.EPOCH,
                new Category<>(Status.AVAILABLE, Instant.EPOCH, "catalog", "node", null, functions, 0),
                Category.disabled(), Category.disabled());
    }

    @Test void emptyAvailableIsDifferentFromDisabled() {
        NodeInformation got = codec.decode(codec.encode(snapshot(List.of())), "a");
        assertThat(got.functions().status()).isEqualTo(Status.AVAILABLE);
        assertThat(got.functions().data()).isEmpty();
        assertThat(got.images().status()).isEqualTo(Status.DISABLED);
        assertThat(got.images().data()).isNull();
    }

    @Test void rejectsIdentityMismatchVersionsAndOversizedPayloads() {
        byte[] bytes = codec.encode(snapshot(List.of()));
        assertThatThrownBy(() -> codec.decode(bytes, "b")).isInstanceOf(IllegalArgumentException.class);
        String json = new String(bytes, StandardCharsets.UTF_8).replace("\"schemaVersion\":1", "\"schemaVersion\":2");
        assertThatThrownBy(() -> codec.decode(json.getBytes(StandardCharsets.UTF_8), "a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode(new byte[1048577], "a")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void oversizedCategoryDoesNotEraseOtherCategories() {
        List<FunctionInfo> functions = new ArrayList<>();
        for (int i = 0; i < 5001; i++) functions.add(new FunctionInfo("fn" + i, "LOCAL", null, null));
        NodeInformation got = codec.decode(codec.encode(snapshot(functions)), "a");
        assertThat(got.functions().reasonCode()).isEqualTo("LIMIT_EXCEEDED");
        assertThat(got.functions().data()).isNull();
        assertThat(got.images().status()).isEqualTo(Status.DISABLED);
    }

    @Test void rejectsMissingStructureAndAcceptsAdditiveFields() {
        assertThatThrownBy(() -> codec.decode("{}".getBytes(StandardCharsets.UTF_8), "a"))
                .isInstanceOf(IllegalArgumentException.class);
        String json = new String(codec.encode(snapshot(List.of())), StandardCharsets.UTF_8);
        json = "{\"futureField\":true," + json.substring(1);
        assertThat(codec.decode(json.getBytes(StandardCharsets.UTF_8), "a").nodeId()).isEqualTo("a");
    }
}
