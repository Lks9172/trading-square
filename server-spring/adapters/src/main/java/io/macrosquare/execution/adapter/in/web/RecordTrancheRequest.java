package io.macrosquare.execution.adapter.in.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Transport-only command DTO; domain/application layers never see Bean Validation types. */
public record RecordTrancheRequest(
        @NotBlank @Size(max = 64) String asset,
        @Min(1) @Max(5) int stage,
        @Positive Double priceAtEntry
) {
}
