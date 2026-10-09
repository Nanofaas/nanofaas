package it.unimib.datai.nanofaas.common.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;

public record ResourceSpec(
        @Valid ResourceQuantity requests,
        @Valid ResourceQuantity limits
) {
    @JsonIgnore
    @AssertTrue(message = "resource request must not exceed limit")
    public boolean isRequestWithinLimit() {
        if (requests == null || limits == null) {
            return true;
        }
        return notGreater(requests.cpu(), limits.cpu())
                && notGreater(requests.memoryMiB(), limits.memoryMiB());
    }

    private static <T extends Comparable<T>> boolean notGreater(T request, T limit) {
        return request == null || limit == null || request.compareTo(limit) <= 0;
    }
}
