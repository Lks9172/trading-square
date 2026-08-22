package io.macrosquare.execution.application.model;

public record PatchValue<T>(boolean present, T value) {
    public static <T> PatchValue<T> missing() {
        return new PatchValue<>(false, null);
    }

    public static <T> PatchValue<T> of(T value) {
        return new PatchValue<>(true, value);
    }
}
