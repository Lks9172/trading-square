package io.macrosquare.market.domain.regime;

/** Market bounded-context language for the current macro regime. */
public enum MacroRegime {
    RISK_ON,
    NEUTRAL,
    CAUTION,
    CORRECTION,
    PANIC_BUT_OK,
    RECESSION_RISK,
    STAGFLATION,
    BOND_VIGILANTE,
    STAGFLATION_BOND_VIGILANTE
}
