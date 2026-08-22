package io.macrosquare.notification.domain;

import java.time.LocalDate;
import java.util.List;

public record InvestmentCandidate(
        CandidateKind kind,
        String symbol,
        String name,
        String classification,
        BottomCandidateState bottomState,
        Integer bottomScore,
        int totalScore,
        int buyScore,
        String action,
        LocalDate signalDate,
        String reversalStatus,
        Integer reversalScore,
        List<String> reasons,
        TechnicalTimingEvidence technicalTiming
) {
    public InvestmentCandidate {
        if (kind == null) throw new IllegalArgumentException("kind is required");
        if (symbol == null || symbol.isBlank()) throw new IllegalArgumentException("symbol is required");
        if (name == null || name.isBlank()) name = symbol;
        if (classification == null) classification = "";
        if (bottomState == null) bottomState = BottomCandidateState.UNMET;
        if (bottomScore != null && (bottomScore < 0 || bottomScore > 100)) {
            throw new IllegalArgumentException("bottomScore must be between 0 and 100");
        }
        if (totalScore < 0 || totalScore > 100 || buyScore < 0 || buyScore > 100) {
            throw new IllegalArgumentException("scores must be between 0 and 100");
        }
        if (action == null) action = "";
        if (reversalStatus == null) reversalStatus = "OFF";
        if (reversalScore != null && (reversalScore < 0 || reversalScore > 100)) {
            throw new IllegalArgumentException("reversalScore must be between 0 and 100");
        }
        reasons = List.copyOf(reasons == null ? List.of() : reasons);
    }

    /** Compatibility constructor for candidates captured before MACD notification evidence. */
    public InvestmentCandidate(
            CandidateKind kind,
            String symbol,
            String name,
            String classification,
            BottomCandidateState bottomState,
            Integer bottomScore,
            int totalScore,
            int buyScore,
            String action,
            LocalDate signalDate,
            String reversalStatus,
            Integer reversalScore,
            List<String> reasons
    ) {
        this(
                kind, symbol, name, classification, bottomState, bottomScore,
                totalScore, buyScore, action, signalDate, reversalStatus, reversalScore,
                reasons, null
        );
    }

    public String key() {
        return kind.name().toLowerCase(java.util.Locale.ROOT) + ":" + symbol;
    }

    /**
     * Removes every field capable of authorizing a new entry while retaining
     * only identity and bounded diagnostic context.
     */
    public InvestmentCandidate failClosed(String reason) {
        var explanation = reason == null || reason.isBlank()
                ? "현재 근거 재검증 실패로 알림 후보에서 제외됨"
                : reason.trim();
        return new InvestmentCandidate(
                kind, symbol, name, classification,
                BottomCandidateState.UNMET, null, 0, 0, "HOLD", null,
                "OFF", null, List.of(explanation), null
        );
    }
}
