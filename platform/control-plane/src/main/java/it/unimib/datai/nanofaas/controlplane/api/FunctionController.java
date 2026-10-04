package it.unimib.datai.nanofaas.controlplane.api;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatus;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionService;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionUpdateRequest;
import it.unimib.datai.nanofaas.controlplane.registry.RegisteredFunction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.Optional;

@RestController
@RequestMapping("/v1/functions")
@Validated
// ResponseEntity<Object> hides these bodies from AOT's return-type inference, so a native image
// had no metadata for them: GET /{name}/replicas answered 500 with "Record components not
// available for record class ReplicaStatusResponse". FunctionResponse is listed too, rather than
// relying on list() and get() happening to declare it.
@RegisterReflectionForBinding({FunctionResponse.class, ReplicaResponse.class, ReplicaStatusResponse.class})
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
    public ResponseEntity<Object> register(@Valid @RequestBody FunctionSpec spec) {
        try {
            return functionService.register(spec)
                    .map(registered -> ResponseEntity.status(HttpStatus.CREATED).<Object>body(FunctionResponse.from(registered)))
                    .orElse(ResponseEntity.status(HttpStatus.CONFLICT).body(ApiErrorResponses.body(
                            "FUNCTION_ALREADY_EXISTS", "Function already exists")));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ApiErrorResponses.body("BAD_REQUEST", ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiErrorResponses.body("SERVICE_UNAVAILABLE", ex.getMessage()));
        }
    }

    @GetMapping("/{name}")
    public ResponseEntity<Object> get(
            @PathVariable @NotBlank(message = "Function name is required") String name) {
        return functionService.getRegistered(name)
                .map(FunctionResponse::from)
                .map(response -> ResponseEntity.<Object>ok(response))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                        ApiErrorResponses.body("FUNCTION_NOT_FOUND", "Function not found")));
    }

    @PatchMapping("/{name}")
    public ResponseEntity<Object> update(
            @PathVariable @NotBlank(message = "Function name is required") String name,
            @Valid @RequestBody FunctionUpdateRequest request) {
        try {
            Optional<RegisteredFunction> updated = functionService.update(name, request);
            if (updated.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                        ApiErrorResponses.body("FUNCTION_NOT_FOUND", "Function not found"));
            }
            return ResponseEntity.status(HttpStatus.OK).<Object>body(FunctionResponse.from(updated.get()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ApiErrorResponses.body("BAD_REQUEST", ex.getMessage()));
        }
    }

    @PutMapping("/{name}/replicas")
    public ResponseEntity<Object> setReplicas(
            @PathVariable @NotBlank(message = "Function name is required") String name,
            @Valid @RequestBody ReplicaRequest request) {
        try {
            Optional<Integer> replicas = functionService.setReplicas(name, request.replicas());
            if (replicas.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                        ApiErrorResponses.body("FUNCTION_NOT_FOUND", "Function not found"));
            }
            return ResponseEntity.status(HttpStatus.OK).<Object>body(new ReplicaResponse(name, replicas.get()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ApiErrorResponses.body("BAD_REQUEST", ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiErrorResponses.body("SERVICE_UNAVAILABLE", ex.getMessage()));
        }
    }

    @GetMapping("/{name}/replicas")
    public ResponseEntity<Object> getReplicas(
            @PathVariable @NotBlank(message = "Function name is required") String name) {
        try {
            Optional<ReplicaStatus> status = functionService.getReplicaStatus(name);
            if (status.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                        ApiErrorResponses.body("FUNCTION_NOT_FOUND", "Function not found"));
            }
            return ResponseEntity.status(HttpStatus.OK).<Object>body(new ReplicaStatusResponse(
                    name,
                    status.get().desiredReplicas(),
                    status.get().readyReplicas()
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ApiErrorResponses.body("BAD_REQUEST", ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiErrorResponses.body("SERVICE_UNAVAILABLE", ex.getMessage()));
        }
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Object> delete(
            @PathVariable @NotBlank(message = "Function name is required") String name) {
        if (functionService.remove(name).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                        ApiErrorResponses.body("FUNCTION_NOT_FOUND", "Function not found"));
        }
        return ResponseEntity.noContent().build();
    }
}
