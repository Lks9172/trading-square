package io.macrosquare.research.application.port.out;

import io.macrosquare.research.domain.rotation.SectorRotationForwardWindow;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

public interface LoadSectorRotationPriceWindowPort {
    Optional<LocalDate> latestCompletedCommonDate(Instant calculatedAt);

    Optional<SectorRotationForwardWindow> loadForwardWindow(LocalDate startOn, int tradingSessions);

    static LoadSectorRotationPriceWindowPort unavailable() {
        return new LoadSectorRotationPriceWindowPort() {
            @Override public Optional<LocalDate> latestCompletedCommonDate(Instant calculatedAt) {
                return Optional.empty();
            }
            @Override public Optional<SectorRotationForwardWindow> loadForwardWindow(
                    LocalDate startOn, int tradingSessions) {
                return Optional.empty();
            }
        };
    }
}
