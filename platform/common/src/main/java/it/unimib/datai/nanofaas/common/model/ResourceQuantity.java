package it.unimib.datai.nanofaas.common.model;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record ResourceQuantity(
        @Positive @Digits(integer = 9, fraction = 3) BigDecimal cpu,
        @Positive Integer memoryMiB
) {
}
