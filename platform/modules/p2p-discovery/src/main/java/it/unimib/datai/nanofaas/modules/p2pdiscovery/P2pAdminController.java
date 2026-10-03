package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Always registered (see {@link P2pAdminGate}); answers only when nanofaas.p2p.enabled and nanofaas.p2p.admin.enabled are true. */
@RestController
@RequestMapping("/v1/admin/p2p")
public class P2pAdminController {
    private static final String ERROR_KEY = "error";

    private final PeerTable table;
    private final P2pSettings settings;
    private final P2pService service;

    public P2pAdminController(PeerTable table, P2pSettings settings, P2pService service) {
        this.service = service;
        this.table = table;
        this.settings = settings;
    }

    public record PeerView(String id, String address, String mode, Double rttMs, List<Double> coord,
                           boolean active, String reason) {}

    @GetMapping("/state")
    public ResponseEntity<Map<String, String>> state() {
        return ResponseEntity.ok(Map.of("state", service.state().name()));
    }

    @PutMapping("/state")
    public Mono<ResponseEntity<Object>> putState(@RequestBody Map<String, Object> body) {
        P2pService.State target;
        try {
            if (body.size() != 1 || !(body.get("state") instanceof String value)) {
                throw new IllegalArgumentException();
            }
            target = P2pService.State.valueOf(value);
            if (target == P2pService.State.DISABLED) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of(ERROR_KEY, "state must be ACTIVE, ISOLATED or LEFT")));
        }
        return Mono.fromCallable(() -> ResponseEntity.ok((Object) Map.of("state", service.setState(target).name())))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(IllegalStateException.class, e -> Mono.just(ResponseEntity.status(409)
                        .body(Map.of(ERROR_KEY, e.getMessage()))));
    }

    @GetMapping("/peers")
    public ResponseEntity<List<PeerView>> peers() {
        return ResponseEntity.ok(table.snapshot().stream()
                .map(p -> new PeerView(p.id(), p.address(), p.mode().name(), p.rttMs(), p.coord(), p.active(), p.reason()))
                .toList());
    }

    @PutMapping("/peers/{id}")
    public ResponseEntity<Object> putPeer(@PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        PeerMode mode;
        try {
            mode = PeerMode.valueOf(String.valueOf(body.get("mode")));
        } catch (IllegalArgumentException _) {
            return ResponseEntity.badRequest().body(Map.of(ERROR_KEY, "mode must be one of AUTO, FORCE_ACTIVE, EXCLUDED"));
        }
        table.setApiMode(id, mode == PeerMode.AUTO ? null : mode);
        service.requestPersist();
        return ResponseEntity.ok(Map.of("id", id, "mode", mode.name()));
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> config() {
        return ResponseEntity.ok(view());
    }

    @PatchMapping("/config")
    public ResponseEntity<Object> patchConfig(@RequestBody Map<String, Object> body) {
        try {
            settings.patch(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(ERROR_KEY, e.getMessage()));
        }
        table.recompute();
        service.requestPersist();
        return ResponseEntity.ok(view());
    }

    @DeleteMapping("/overrides")
    public ResponseEntity<Object> deleteOverrides() {
        settings.clearOverrides();
        table.loadApiModes(Map.of());
        service.requestPersist();
        return ResponseEntity.ok(view());
    }

    private Map<String, Object> view() {
        NeighborSelector.Settings e = settings.effective();
        Map<String, Object> effective = new HashMap<>();
        effective.put("maxNeighbors", e.maxNeighbors());
        effective.put("maxLatencyMs", e.maxLatencyMs());
        P2pSettings.Sharing sharing = settings.sharing();
        effective.put("shareFunctions", sharing.functions());
        effective.put("shareImages", sharing.images());
        effective.put("shareResources", sharing.resources());
        return Map.of("effective", effective, "overrides", settings.overrides());
    }
}
