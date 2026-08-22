package io.macrosquare.company.adapter.in.web;

import io.macrosquare.company.domain.model.CompanyScore;
import io.macrosquare.company.domain.model.ScoreBreakdown;

import java.util.List;

public record CompanyScoreResponse(
        String ticker,
        int totalScore,
        ScoreBreakdownResponse growth,
        ScoreBreakdownResponse quality,
        ScoreBreakdownResponse valuation,
        ScoreBreakdownResponse balanceSheet,
        List<String> reasons
) {
    static CompanyScoreResponse from(CompanyScore score) {
        return new CompanyScoreResponse(
                score.ticker().value(),
                score.totalScore(),
                ScoreBreakdownResponse.from(score.growth()),
                ScoreBreakdownResponse.from(score.quality()),
                ScoreBreakdownResponse.from(score.valuation()),
                ScoreBreakdownResponse.from(score.balanceSheet()),
                score.reasons()
        );
    }

    public record ScoreBreakdownResponse(int value, List<String> reasons) {
        static ScoreBreakdownResponse from(ScoreBreakdown score) {
            return new ScoreBreakdownResponse(score.value(), score.reasons());
        }
    }
}
