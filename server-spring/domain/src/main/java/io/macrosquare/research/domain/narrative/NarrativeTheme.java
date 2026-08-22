package io.macrosquare.research.domain.narrative;

public enum NarrativeTheme {
    AI_POWER("ai-power"),
    GRID_CAPEX("grid-capex"),
    DEFENSE_REARM("defense-rearm"),
    FINANCE_LIQUIDITY("finance-liquidity"),
    ENERGY_SUPPLY("energy-supply"),
    DIGITAL_ATTENTION("digital-attention"),
    CONSUMER_DEMAND("consumer-demand"),
    CONSUMER_DEFENSIVE("consumer-defensive"),
    MATERIALS_REFLATION("materials-reflation"),
    REAL_ASSETS_RATE("real-assets-rate"),
    SAFEHAVEN_GOLD("safehaven-gold");

    private final String id;

    NarrativeTheme(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static NarrativeTheme fromId(String id) {
        for (var theme : values()) {
            if (theme.id.equals(id)) return theme;
        }
        throw new IllegalArgumentException("Unknown narrative theme id: " + id);
    }
}
