package io.macrosquare.research.domain.rotation;

public record RotationMarketEvidence(
        Double liquidityDirection,
        Double realYield,
        Double yieldCurve10y2y,
        Double wti,
        Double dollarIndex,
        Double financialStressIndex,
        Double highYieldOas,
        Double highYieldOasBasisPoints,
        Boolean overheated,
        Boolean copperGoldRatioUpturn,
        MacroRegime macroRegime,
        Double institutionalTechFlow,
        Double institutionalFinancialFlow,
        Double institutionalEnergyFlow
) {
    public RotationMarketEvidence {
        requireFinite(liquidityDirection, "liquidityDirection");
        requireFinite(realYield, "realYield");
        requireFinite(yieldCurve10y2y, "yieldCurve10y2y");
        requireFinite(wti, "wti");
        requireFinite(dollarIndex, "dollarIndex");
        requireFinite(financialStressIndex, "financialStressIndex");
        requireFinite(highYieldOas, "highYieldOas");
        requireFinite(highYieldOasBasisPoints, "highYieldOasBasisPoints");
        requireFinite(institutionalTechFlow, "institutionalTechFlow");
        requireFinite(institutionalFinancialFlow, "institutionalFinancialFlow");
        requireFinite(institutionalEnergyFlow, "institutionalEnergyFlow");
        if (macroRegime == null) throw new IllegalArgumentException("macroRegime is required");
    }

    private static void requireFinite(Double value, String field) {
        if (value != null && !Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be finite");
        }
    }
}
