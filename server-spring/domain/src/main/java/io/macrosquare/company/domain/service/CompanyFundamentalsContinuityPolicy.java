package io.macrosquare.company.domain.service;

import io.macrosquare.company.domain.model.CompanyFundamentalsEvidence;
import io.macrosquare.company.domain.model.FinancialFactPoint;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Merges semantic facts across a legal-entity CIK succession without exposing
 * SEC identifiers or taxonomy rules to the normalization policy.
 *
 * <p>Inputs are ordered newest legal entity first. Exact duplicate facts are
 * removed while that order is retained, so a successor's current statement
 * wins and a predecessor contributes only the missing history.</p>
 */
public final class CompanyFundamentalsContinuityPolicy {

    public CompanyFundamentalsEvidence merge(List<CompanyFundamentalsEvidence> orderedEvidence) {
        var evidence = List.copyOf(Objects.requireNonNull(orderedEvidence, "orderedEvidence"));
        if (evidence.isEmpty()) throw new IllegalArgumentException("orderedEvidence must not be empty");
        return new CompanyFundamentalsEvidence(
                mergeSeries(evidence.stream().map(CompanyFundamentalsEvidence::revenue).toList()),
                mergeSeries(evidence.stream().map(CompanyFundamentalsEvidence::operatingIncome).toList()),
                mergeSeries(evidence.stream().map(CompanyFundamentalsEvidence::netIncome).toList()),
                mergeSeries(evidence.stream().map(CompanyFundamentalsEvidence::operatingCashFlow).toList()),
                mergeSeries(evidence.stream().map(CompanyFundamentalsEvidence::capitalExpenditure).toList()),
                mergeSeries(evidence.stream().map(CompanyFundamentalsEvidence::cash).toList()),
                mergeSeries(evidence.stream().map(CompanyFundamentalsEvidence::debt).toList()),
                mergeSeries(evidence.stream().map(CompanyFundamentalsEvidence::sharesOutstanding).toList()),
                mergeSeries(evidence.stream().map(CompanyFundamentalsEvidence::stockCompensation).toList()),
                mergeSeries(evidence.stream().map(CompanyFundamentalsEvidence::stockholdersEquity).toList()),
                mergeSeries(evidence.stream().map(CompanyFundamentalsEvidence::currentAssets).toList()),
                mergeSeries(evidence.stream().map(CompanyFundamentalsEvidence::currentLiabilities).toList()),
                mergeSeries(evidence.stream().map(CompanyFundamentalsEvidence::receivables).toList()),
                mergeSeries(evidence.stream().map(CompanyFundamentalsEvidence::inventory).toList()),
                mergeSeries(evidence.stream().map(CompanyFundamentalsEvidence::pretaxIncome).toList()),
                mergeSeries(evidence.stream().map(CompanyFundamentalsEvidence::incomeTaxExpense).toList()),
                mergeSeries(evidence.stream().map(CompanyFundamentalsEvidence::totalAssets).toList()),
                mergeSeries(evidence.stream().map(CompanyFundamentalsEvidence::weightedAverageDilutedShares).toList()),
                mergeSeries(evidence.stream().map(CompanyFundamentalsEvidence::costsAndExpenses).toList())
        );
    }

    private static List<FinancialFactPoint> mergeSeries(List<List<FinancialFactPoint>> series) {
        var result = new ArrayList<FinancialFactPoint>();
        var periodsOwnedByNewerEntities = new HashSet<FactPeriod>();
        for (var source : series) {
            var periodsInThisEntity = new HashSet<FactPeriod>();
            for (var point : source) {
                var period = FactPeriod.from(point);
                if (!periodsOwnedByNewerEntities.contains(period)) result.add(point);
                periodsInThisEntity.add(period);
            }
            periodsOwnedByNewerEntities.addAll(periodsInThisEntity);
        }
        return List.copyOf(result);
    }

    private record FactPeriod(String form, String fiscalPeriod, String startDate, String endDate) {
        private static FactPeriod from(FinancialFactPoint point) {
            return new FactPeriod(point.form(), point.fiscalPeriod(), point.startDate(), point.endDate());
        }
    }
}
