package it.unimib.datai.nanofaas.controlplane.api;

import it.unimib.datai.nanofaas.common.model.RawJson;
import it.unimib.datai.nanofaas.controlplane.input.RetainedInputEstimator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;


import static org.assertj.core.api.Assertions.assertThat;

class RawJsonDeserializerTest {
    private static final JsonMapper MAPPER = JsonMapper.shared();

    private static RawJson input(String json) {
        return MAPPER.readValue("{\"input\": " + json + ", \"metadata\": {\"k\": \"v\"}}",
                IngressInvocationRequest.class).input();
    }

    @Test
    void thePayloadIsKeptAsJsonTextAndTheEnvelopeAroundItStillBinds() {
        var request = MAPPER.readValue(
                "{\"metadata\": {\"k\": \"v\"}, \"input\": {\"a\": [1, 2.50, {\"b\": null}], \"città\": \"x\\ny\"}, \"headers\": {\"h\": \"1\"}}",
                IngressInvocationRequest.class);

        assertThat(request.input().json()).isEqualTo("{\"a\":[1,2.50,{\"b\":null}],\"città\":\"x\\ny\"}");
        assertThat(request.metadata()).containsEntry("k", "v");
        assertThat(request.headers()).containsEntry("h", "1");
    }

    @Test
    void numbersKeepTheDigitsTheyWereWrittenWith() {
        String numbers = "[0,-1,2.50,1e3,1.0E-7,12345678901234567890123,0.1000000000000000055511151231257827]";

        assertThat(input(numbers).json()).isEqualTo(numbers);
    }

    @Test
    void aMissingOrNullPayloadIsNullSoValidationRefusesIt() {
        assertThat(MAPPER.readValue("{\"input\": null}", IngressInvocationRequest.class).input()).isNull();
        assertThat(MAPPER.readValue("{}", IngressInvocationRequest.class).input()).isNull();
    }

    /**
     * The shape counts are what the admission limits (depth, container width, visited nodes) are
     * compared against, so they are part of the API contract. These are the values the object-tree
     * representation produced for the same documents before payloads were kept as text; the two
     * were checked against each other document by document before that representation was removed.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
            "1  | 0 | 0 | \"scalar\"",
            "1  | 0 | 0 | 42",
            "1  | 0 | 0 | true",
            "2  | 1 | 1 | []",
            "2  | 1 | 1 | {}",
            "5  | 1 | 4 | [1, 2, 3]",
            "6  | 1 | 5 | {\"a\": 1, \"b\": 2}",
            "13 | 3 | 5 | {\"a\": [1, 2, {\"b\": null}], \"s\": \"x\"}",
            "23 | 5 | 6 | [[[[1]]], {\"k\": {\"k\": {\"k\": []}}}, \"s\", 1.5, false]",
            "21 | 4 | 5 | {\"records\": [{\"id\": 1, \"tags\": [\"a\", \"b\"]}, {\"id\": 2, \"tags\": []}]}"
    })
    void shapeCountsAreTheSameWhetherThePayloadIsCopiedOrCutOutOfTheRequest(int nodes, int depth, int width, String json) {
        RawJson copied = input(json);
        byte[] body = ("{\"metadata\": {}, \"input\":  " + json + " }").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        RawJson sliced = ((IngressInvocationRequest) MAPPER.readerFor(IngressInvocationRequest.class)
                .withAttribute(RawJsonDeserializer.SOURCE, body).readValue(body)).input();

        assertThat(new int[]{copied.nodes(), copied.depth(), copied.width()}).containsExactly(nodes, depth, width);
        assertThat(new int[]{sliced.nodes(), sliced.depth(), sliced.width()}).containsExactly(nodes, depth, width);
        // Cut out of the request bytes, the payload is the caller's exact text.
        assertThat(sliced.json()).isEqualTo(json);
        // Accepted at exactly these limits.
        var exact = new RetainedInputEstimator.Limits(depth, Math.max(1, width), nodes, 1_000_000);
        assertThat(new RetainedInputEstimator(exact).estimate(sliced)).isInstanceOf(RetainedInputEstimator.Measured.class);
    }
}
