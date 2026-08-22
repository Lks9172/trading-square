package io.macrosquare.research.domain.rotation;

public enum SectorRotationLabel {
    ROTATION_IN("Rotation In"),
    LEADER("Leader"),
    LATE_LEADER("Late Leader"),
    ROTATION_OUT("Rotation Out"),
    DEFENSIVE_HOLD("Defensive Hold");

    private final String displayName;

    SectorRotationLabel(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
