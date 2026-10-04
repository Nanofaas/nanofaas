package it.unimib.datai.nanofaas.modules.forecasting;

import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/v1/admin/forecasting/trace")
@RegisterReflectionForBinding({OracleForecastStore.Trace.class, OracleForecastStore.Entry.class, OracleForecastStore.Summary.class})
public final class ForecastController {
    static final int MAX_BYTES = 8 * 1024 * 1024;
    private final OracleForecastStore store;
    private final JsonMapper mapper = JsonMapper.builder(JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(256).maxNumberLength(64).build()).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    public ForecastController(OracleForecastStore store) { this.store = store; }
    @GetMapping public OracleForecastStore.Summary get() { return store.summary(); }
    @PutMapping(consumes = "application/json")
    public Mono<ResponseEntity<OracleForecastStore.Summary>> replace(@RequestHeader("If-Match") long expected,
                                                                     @RequestBody Flux<DataBuffer> body) {
        return DataBufferUtils.join(body, MAX_BYTES).map(buffer -> {
            byte[] bytes = new byte[buffer.readableByteCount()];
            try { buffer.read(bytes); }
            finally { DataBufferUtils.release(buffer); }
            store.replace(expected, mapper.readValue(bytes, OracleForecastStore.Trace.class));
            return ResponseEntity.ok(store.summary());
        }).switchIfEmpty(Mono.just(ResponseEntity.badRequest().build()))
                .onErrorResume(OracleForecastStore.RevisionConflict.class, e -> Mono.just(ResponseEntity.status(409).build()))
                .onErrorResume(DataBufferLimitException.class, e -> Mono.just(ResponseEntity.status(413).build()))
                .onErrorResume(IllegalArgumentException.class, e -> Mono.just(ResponseEntity.badRequest().build()))
                .onErrorResume(tools.jackson.core.JacksonException.class, e -> Mono.just(ResponseEntity.badRequest().build()));
    }
}
