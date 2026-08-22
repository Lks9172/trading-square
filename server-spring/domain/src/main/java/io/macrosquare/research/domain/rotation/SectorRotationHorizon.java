package io.macrosquare.research.domain.rotation;

public enum SectorRotationHorizon {
    NOW("now"),
    ONE_TO_THREE_MONTHS("1_3m"),
    THREE_TO_SIX_MONTHS("3_6m"),
    SIX_MONTHS_PLUS("6m_plus"),
    UNCLEAR("unclear");

    private final String code;

    SectorRotationHorizon(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
