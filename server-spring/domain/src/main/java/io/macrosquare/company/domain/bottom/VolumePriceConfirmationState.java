package io.macrosquare.company.domain.bottom;

/** Independent OBV/VWAP confirmation state; it does not redefine the bottom signal. */
public enum VolumePriceConfirmationState {
    ACCUMULATION,
    NEUTRAL,
    DISTRIBUTION,
    UNAVAILABLE
}
