package it.unimib.datai.nanofaas.controlplane.api;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;

@RestController
@RequestMapping("/v1/functions")
@Validated
public class FunctionController {
    private final FunctionService functionService;

    public FunctionController(FunctionService functionService) {
        this.functionService = functionService;
    }

    @GetMapping
    public Collection<FunctionResponse> list() {
        return functionService.listRegistered().stream()
                .map(FunctionResponse::from)
                .toList();
    }

    @PostMapping
    public ResponseEntity<?> register(@Valid @RequestBody FunctionSpec spec) {
        try {
            return functionService.register(spec)
                    .flatMap(ignored -> functionService.getRegistered(spec.name()))
                    .map(registered -> ResponseEntity.status(HttpStatus.CREATED).body(FunctionResponse.from(registered)))
                    .orElse(ResponseEntity.status(HttpStatus.CONFLICT).build());
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ex.getMessage());
        }
    }

    @GetMapping("/{name}")
    public ResponseEntity<FunctionResponse> get(
            @PathVariable @NotBlank(message = "Function name is required") String name) {
        return functionService.getRegistered(name)
                .map(FunctionResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{name}/replicas")
    public ResponseEntity<?> setReplicas(
            @PathVariable @NotBlank(message = "Function name is required") String name,
            @Valid @RequestBody ReplicaRequest request) {
        try {
            return functionService.setReplicas(name, request.replicas())
                    .map(r -> ResponseEntity.ok(new ReplicaResponse(name, r)))
                    .orElse(ResponseEntity.notFound().build());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ex.getMessage());
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ex.getMessage());
        }
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(
            @PathVariable @NotBlank(message = "Function name is required") String name) {
        if (functionService.remove(name).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }
}
