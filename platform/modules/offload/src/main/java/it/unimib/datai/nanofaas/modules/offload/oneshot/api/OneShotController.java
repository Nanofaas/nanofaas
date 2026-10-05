package it.unimib.datai.nanofaas.modules.offload.oneshot.api;
import it.unimib.datai.nanofaas.modules.offload.oneshot.coordination.ClockHealth;
import it.unimib.datai.nanofaas.modules.offload.oneshot.actuation.PlanActivation;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.core.io.buffer.*;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import reactor.core.publisher.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.*;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import java.time.*;
import java.util.*;
@RestController
@RequestMapping("/v1/admin/offload/one-shot")
@RegisterReflectionForBinding({OneShotSettings.class,OneShotSettings.Function.class,it.unimib.datai.nanofaas.modules.offload.oneshot.coordination.EpochSettings.class,it.unimib.datai.nanofaas.p2papi.PeerEndpoint.class,OneShotConfigurationStore.Snapshot.class,ServiceProfileStore.Profile.class,ServiceProfileStore.Function.class,ServiceProfileStore.Environment.class,ServiceProfileStore.Statistics.class,ServiceProfileStore.Validity.class,ServiceProfileStore.Measurement.class,ServiceProfileStore.Stored.class,EpochEventStore.Event.class,EpochEventStore.Entry.class,OneShotController.ClockSample.class,OneShotOperations.Window.class,PlanActivation.class,it.unimib.datai.nanofaas.modules.offload.oneshot.actuation.ActiveRoutingPlan.class,it.unimib.datai.nanofaas.modules.offload.oneshot.actuation.ActiveRoutingPlan.FunctionPlan.class})
public final class OneShotController {
    public record ClockSample(Duration offset,Instant measuredAt) {}
    private final boolean enabled;
    private final OneShotOperations operations;private final ServiceProfileStore profiles;private final EpochEventStore events;private final ClockHealth clock;
    private final JsonMapper mapper=JsonMapper.builder(JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(12).maxStringLength(256).maxNumberLength(64).build()).build())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS,DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    public OneShotController(OneShotOperations operations,ServiceProfileStore profiles,EpochEventStore events,ClockHealth clock) { this(operations,profiles,events,clock,true); }
    public OneShotController(OneShotOperations operations,ServiceProfileStore profiles,EpochEventStore events,ClockHealth clock,boolean enabled) { this.operations=operations;this.profiles=profiles;this.events=events;this.clock=clock;this.enabled=enabled; }
    @ModelAttribute public void requireEnabled() { if(!enabled) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND); }
    private Mono<byte[]> body(Flux<DataBuffer> body) {
        return DataBufferUtils.join(body,8*1024*1024).map(buffer->{byte[] bytes=new byte[buffer.readableByteCount()];try {buffer.read(bytes);}finally {DataBufferUtils.release(buffer);}return bytes;}).switchIfEmpty(Mono.error(new IllegalArgumentException("body missing")));
    }
    @PutMapping(value="/config",consumes="application/json")
    public Mono<OneShotConfigurationStore.Snapshot> configure(@RequestHeader("If-Match") long revision,@RequestBody Flux<DataBuffer> body) {
        return body(body).map(bytes->operations.configure(revision,mapper.readValue(bytes,OneShotSettings.class)));
    }
    @PutMapping(value="/profiles/{id}",consumes="application/json")
    public Mono<ServiceProfileStore.Stored> profile(@PathVariable("id") String id,@RequestHeader("If-Match") long revision,@RequestHeader("X-Content-SHA256") String hash,@RequestBody Flux<DataBuffer> body) {
        return body(body).map(bytes->profiles.replace(id,revision,hash,bytes));
    }
    @PostMapping("/drain-and-release") public Mono<Boolean> release() { return operations.drainAndRelease(); }
    @GetMapping("/status") public Map<String,Object> status() { return operations.status(); }
    @GetMapping("/epochs/{epoch}/events") public Map<String,Object> events(@PathVariable("epoch") long epoch,@RequestParam(value="after",defaultValue="0") long after,@RequestParam(value="limit",defaultValue="100") int limit) {
        var entries=events.page(epoch,after,limit);return Map.of("schemaVersion",1,"contentHash",ServiceProfileStore.hash(mapper.writeValueAsBytes(entries)),"entries",entries);
    }
    @PutMapping(value="/clock-health",consumes="application/json") public Mono<Map<String,Object>> clock(@RequestBody Flux<DataBuffer> body) {
        return body(body).map(bytes->{var sample=mapper.readValue(bytes,OneShotController.ClockSample.class);clock.sample(sample.offset(),sample.measuredAt());return Map.of("healthy",clock.healthy());});
    }
    @PostMapping(value="/epochs/{epoch}/prepare",consumes="application/json") public Mono<PlanActivation> prepare(@PathVariable("epoch") long epoch,@RequestBody Flux<DataBuffer> body) {
        return body(body).flatMap(bytes->operations.prepare(epoch,mapper.readValue(bytes,OneShotOperations.Window.class),false));
    }
    @ExceptionHandler({IllegalArgumentException.class,tools.jackson.core.JacksonException.class}) public ResponseEntity<Map<String,String>> invalid(Exception error) { String message=error.getMessage()==null?"invalid input":error.getMessage();return ResponseEntity.badRequest().body(Map.of("error","INVALID_ONE_SHOT_INPUT","message",message.substring(0,Math.min(256,message.length())))); }
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<Map<String,String>> conflict(Exception error) { return ResponseEntity.status(409).body(Map.of("error","ONE_SHOT_CONFLICT")); }
    @ExceptionHandler(DataBufferLimitException.class) public ResponseEntity<Void> tooLarge() { return ResponseEntity.status(413).build(); }
}
