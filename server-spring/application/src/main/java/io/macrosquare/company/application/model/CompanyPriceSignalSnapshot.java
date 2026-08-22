package io.macrosquare.company.application.model;

import io.macrosquare.company.domain.bottom.BottomPriceSignal;
import io.macrosquare.company.domain.bottom.DeepBottomSignal;
import io.macrosquare.company.domain.bottom.PriceStructureAnalysis;
import io.macrosquare.company.domain.bottom.ReversalConfirmation;
import io.macrosquare.company.domain.bottom.VolumePriceAnalysis;
import io.macrosquare.company.domain.horizon.CompanyWalkForwardValidation;
import io.macrosquare.technical.domain.MacdMultiTimeframeAnalysis;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Framework-free observable output of one company price-signal calculation. */
public record CompanyPriceSignalSnapshot(
        PriceHistorySummary history,
        List<ChartMarker> markers,
        BottomPriceSignal priceSignal,
        DeepBottomSignal confirmedBottom,
        ReversalConfirmation reversalConfirmation,
        VolumePriceAnalysis technicalConfirmation,
        CompanyWalkForwardValidation walkForwardValidation,
        PriceStructureAnalysis priceStructure,
        MacdMultiTimeframeAnalysis macdMomentum
) {
    public CompanyPriceSignalSnapshot {
        history = Objects.requireNonNull(history, "history");
        markers = List.copyOf(Objects.requireNonNull(markers, "markers"));
        priceSignal = Objects.requireNonNull(priceSignal, "priceSignal");
        confirmedBottom = Objects.requireNonNull(confirmedBottom, "confirmedBottom");
        reversalConfirmation = Objects.requireNonNull(reversalConfirmation, "reversalConfirmation");
    }

    public CompanyPriceSignalSnapshot(
            PriceHistorySummary history,
            List<ChartMarker> markers,
            BottomPriceSignal priceSignal,
            DeepBottomSignal confirmedBottom,
            ReversalConfirmation reversalConfirmation
    ) {
        this(history, markers, priceSignal, confirmedBottom, reversalConfirmation, null, null, null, null);
    }

    public CompanyPriceSignalSnapshot(
            PriceHistorySummary history,
            List<ChartMarker> markers,
            BottomPriceSignal priceSignal,
            DeepBottomSignal confirmedBottom,
            ReversalConfirmation reversalConfirmation,
            VolumePriceAnalysis technicalConfirmation,
            CompanyWalkForwardValidation walkForwardValidation
    ) {
        this(
                history, markers, priceSignal, confirmedBottom, reversalConfirmation,
                technicalConfirmation, walkForwardValidation, null, null
        );
    }

    public CompanyPriceSignalSnapshot(
            PriceHistorySummary history,
            List<ChartMarker> markers,
            BottomPriceSignal priceSignal,
            DeepBottomSignal confirmedBottom,
            ReversalConfirmation reversalConfirmation,
            VolumePriceAnalysis technicalConfirmation,
            CompanyWalkForwardValidation walkForwardValidation,
            PriceStructureAnalysis priceStructure
    ) {
        this(
                history, markers, priceSignal, confirmedBottom, reversalConfirmation,
                technicalConfirmation, walkForwardValidation, priceStructure, null
        );
    }

    public record PriceHistorySummary(
            int pointCount,
            LocalDate firstDate,
            Double firstClose,
            LocalDate lastDate,
            Double lastClose
    ) {
        public PriceHistorySummary {
            if (pointCount < 0) throw new IllegalArgumentException("pointCount must be non-negative");
            requireFinite(firstClose, "firstClose");
            requireFinite(lastClose, "lastClose");
        }

        private static void requireFinite(Double value, String field) {
            if (value != null && !Double.isFinite(value)) {
                throw new IllegalArgumentException(field + " must be finite");
            }
        }
    }

    public record ChartMarker(String kind, LocalDate date, double value) {
        public ChartMarker {
            if (kind == null || kind.isBlank()) throw new IllegalArgumentException("kind is required");
            Objects.requireNonNull(date, "date");
            if (!Double.isFinite(value) || value <= 0) {
                throw new IllegalArgumentException("value must be positive and finite");
            }
        }
    }
}
