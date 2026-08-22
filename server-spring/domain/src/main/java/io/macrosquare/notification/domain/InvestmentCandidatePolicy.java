package io.macrosquare.notification.domain;

import java.util.Comparator;
import java.util.List;

/** Exact Telegram eligibility policy shared by startup and entry alerts. */
public final class InvestmentCandidatePolicy {

    private static final Comparator<InvestmentCandidate> ORDER = Comparator
            .comparingInt((InvestmentCandidate value) -> value.bottomState() == BottomCandidateState.CONVICTION ? 1 : 0)
            .thenComparingInt(value -> value.bottomScore() == null ? -1 : value.bottomScore())
            .thenComparingInt(InvestmentCandidate::buyScore)
            .thenComparingInt(InvestmentCandidate::totalScore)
            .reversed();

    public boolean prequalifies(InvestmentCandidate value) {
        if (value.kind() == CandidateKind.COMPANY) {
            // Company alerts represent evidence strengthening, not an execution order.
            // BUY/REDUCE/HOLD belongs to the execution policy and must not suppress a
            // confirmed bottom/reversal notification requested by the user.
            return value.buyScore() >= 70 && value.totalScore() >= 70;
        }
        return value.buyScore() >= 70 && value.totalScore() >= 70 && validAction(value);
    }

    public boolean qualifies(InvestmentCandidate value) {
        if (!prequalifies(value)) return false;
        if (value.kind() == CandidateKind.COMPANY) {
            return (value.bottomState() == BottomCandidateState.CANDIDATE
                    || value.bottomState() == BottomCandidateState.CONVICTION)
                    && reversalConfirmed(value);
        }
        return value.bottomState() == BottomCandidateState.CANDIDATE
                || value.bottomState() == BottomCandidateState.CONVICTION;
    }

    public List<InvestmentCandidate> qualified(List<InvestmentCandidate> values, int limit) {
        return values.stream().filter(this::qualifies).sorted(ORDER).limit(Math.max(0, limit)).toList();
    }

    /** Detects meaningful strengthening without coupling the domain to Telegram wording. */
    public InvestmentCandidateStrengthening strengthening(
            InvestmentCandidate previous,
            InvestmentCandidate current
    ) {
        if (previous == null || current == null
                || previous.kind() != CandidateKind.COMPANY
                || current.kind() != CandidateKind.COMPANY
                || !previous.key().equals(current.key())
                || !qualifies(previous)
                || !qualifies(current)) {
            return InvestmentCandidateStrengthening.none();
        }
        var previousTotalBand = scoreBand(previous.totalScore());
        var currentTotalBand = scoreBand(current.totalScore());
        var previousBuyBand = scoreBand(previous.buyScore());
        var currentBuyBand = scoreBand(current.buyScore());
        return new InvestmentCandidateStrengthening(
                "ON".equals(previous.reversalStatus()) && "STRONG".equals(current.reversalStatus()),
                currentTotalBand > previousTotalBand ? previousTotalBand : null,
                currentTotalBand > previousTotalBand ? currentTotalBand : null,
                currentBuyBand > previousBuyBand ? previousBuyBand : null,
                currentBuyBand > previousBuyBand ? currentBuyBand : null
        );
    }

    private static boolean validAction(InvestmentCandidate value) {
        if (value.kind() == CandidateKind.CRYPTO) return "STRONG BUY".equals(value.action());
        return "BUY".equals(value.action()) || "STRONG BUY".equals(value.action());
    }

    private static boolean reversalConfirmed(InvestmentCandidate value) {
        return "ON".equals(value.reversalStatus()) || "STRONG".equals(value.reversalStatus());
    }

    private static int scoreBand(int score) {
        if (score < 70) return -1;
        return Math.min(100, score / 5 * 5);
    }
}
