package it.unimib.datai.nanofaas.sdk.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonOutputNormalizerTest {

    private final JsonOutputNormalizer normalizer = new JsonOutputNormalizer(new ObjectMapper());

    @Test
    void toJsonNode_preservesStructuredMapOutput() {
        JsonNode node = normalizer.toJsonNode(Map.of(
                "wordCount", 4,
                "topWords", List.of(Map.of("word", "the", "count", 1))
        ));

        assertEquals(4, node.get("wordCount").intValue());
        assertEquals("the", node.get("topWords").get(0).get("word").asText());
    }

    @Test
    void toJsonNode_preservesNullAsJsonNull() {
        JsonNode node = normalizer.toJsonNode(null);

        assertTrue(node.isNull());
    }

    @Test
    void toJsonNode_wrapsSerializationFailureWithClearException() {
        Object invalid = new Object() {
            public Object getSelf() {
                return this;
            }
        };

        OutputSerializationException ex = assertThrows(
                OutputSerializationException.class,
                () -> normalizer.toJsonNode(invalid)
        );
        assertTrue(ex.getMessage().contains("Function output is not JSON-serializable"));
    }
}
