package it.unimib.datai.mcfaas.controlplane.api;

import it.unimib.datai.mcfaas.common.model.FunctionSpec;
import it.unimib.datai.mcfaas.controlplane.registry.FunctionService;
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
    public Collection<FunctionSpec> list() {
        return functionService.list();
    }

    @PostMapping
    public ResponseEntity<FunctionSpec> register(@Valid @RequestBody FunctionSpec spec) {
        return functionService.register(spec)
                .map(resolved -> ResponseEntity.status(HttpStatus.CREATED).body(resolved))
                .orElse(ResponseEntity.status(HttpStatus.CONFLICT).build());
    }

    @GetMapping("/{name}")
    public ResponseEntity<FunctionSpec> get(
            @PathVariable @NotBlank(message = "Function name is required") String name) {
        return functionService.get(name)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
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
