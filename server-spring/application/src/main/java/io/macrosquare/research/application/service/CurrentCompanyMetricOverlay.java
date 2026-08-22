package io.macrosquare.research.application.service;

import io.macrosquare.research.application.model.CurrentCompanyMetric;
import io.macrosquare.research.application.model.ResearchCatalogModels.CompanyItem;
import io.macrosquare.research.application.model.ResearchCatalogModels.SectorDetail;
import io.macrosquare.research.application.model.ResearchCatalogModels.SectorSummary;
import io.macrosquare.research.application.model.ResearchCatalogModels.ThemeDetail;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class CurrentCompanyMetricOverlay {

    private CurrentCompanyMetricOverlay() {
    }

    static ThemeDetail theme(ThemeDetail source, Map<String, CurrentCompanyMetric> metrics) {
        var items = ranked(source.items(), metrics, source.companySortKey());
        return new ThemeDetail(
                source.theme(), items, source.sectorScores(), summary(source.sectorSummary(), items, metrics),
                source.sortKey(), source.companySortKey());
    }

    static SectorDetail sector(SectorDetail source, Map<String, CurrentCompanyMetric> metrics) {
        var items = ranked(source.items(), metrics, "buy");
        return new SectorDetail(
                source.sector(), source.sortKey(), source.relatedThemes(), source.sectorScores(),
                summary(source.sectorSummary(), items, metrics), source.rotation(), source.rotationSummary(),
                source.densitySummary(), items);
    }

    private static List<CompanyItem> ranked(
            List<CompanyItem> source,
            Map<String, CurrentCompanyMetric> metrics,
            String sort
    ) {
        var overlaid = source.stream().map(item -> overlay(item, metrics.get(normalize(item.ticker())))).toList();
        var sorted = overlaid.stream()
                .sorted(Comparator.comparingDouble((CompanyItem value) -> rank(value, sort)).reversed()
                        .thenComparing(CompanyItem::ticker))
                .toList();
        var result = new ArrayList<CompanyItem>(sorted.size());
        for (var index = 0; index < sorted.size(); index++) {
            var item = sorted.get(index);
            result.add(new CompanyItem(
                    item.ticker(), item.name(), item.marketCap(), item.totalScore(), item.buyScore(),
                    item.buyLabel(), item.appealScore(), item.crowdingScore(), item.revenueGrowthYoY(),
                    item.operatingMargin(), item.evToSales(), item.sectorKey(), item.bottomScore(),
                    item.priceBottomScore(), item.volumeConfirmationScore(), item.failureRiskScore(),
                    item.bottomState(), item.confirmedBottomScore(), item.confirmedBottomState(),
                    index + 1, item.error()));
        }
        return List.copyOf(result);
    }

    private static CompanyItem overlay(CompanyItem source, CurrentCompanyMetric metric) {
        if (metric == null) {
            return new CompanyItem(
                    source.ticker(), source.name(), null, null, null, null, null, null,
                    null, null, null, source.sectorKey(), null, null, null, null,
                    null, null, null, source.rank(), "현재 Spring 기업 지표 계산 대기 중");
        }
        return new CompanyItem(
                source.ticker(), source.name(), metric.marketCap(), metric.totalScore(), metric.buyScore(),
                metric.buyLabel(), metric.appealScore(), metric.crowdingScore(), metric.revenueGrowthYoY(),
                metric.operatingMargin(), metric.evToSales(), source.sectorKey(), metric.confirmedBottomScore(),
                metric.priceBottomScore(), metric.volumeConfirmationScore(), metric.failureRiskScore(),
                bottomState(metric.confirmedBottomState()), metric.confirmedBottomScore(),
                bottomState(metric.confirmedBottomState()), source.rank(),
                metric.totalScore() == null || metric.buyScore() == null
                        ? "핵심 재무 지표가 비교 불가하여 기업/매수 점수를 보류함"
                        : null);
    }

    private static SectorSummary summary(
            SectorSummary captured,
            List<CompanyItem> items,
            Map<String, CurrentCompanyMetric> metrics
    ) {
        var current = items.stream()
                .map(item -> metrics.get(normalize(item.ticker())))
                .filter(java.util.Objects::nonNull)
                .toList();
        if (current.isEmpty()) return new SectorSummary(
                null, null, null, null, null, null, null,
                captured == null ? null : captured.averageRotationScore(),
                captured == null ? null : captured.topSector());
        return new SectorSummary(
                averageInteger(current.stream().map(CurrentCompanyMetric::buyScore).toList()),
                averageInteger(current.stream().map(CurrentCompanyMetric::confirmedBottomScore).toList()),
                averageInteger(current.stream().map(CurrentCompanyMetric::failureRiskScore).toList()),
                averageInteger(current.stream().map(CurrentCompanyMetric::volumeConfirmationScore).toList()),
                averageInteger(current.stream().map(CurrentCompanyMetric::appealScore).toList()),
                averageInteger(current.stream().map(CurrentCompanyMetric::crowdingScore).toList()),
                averageInteger(current.stream().map(CurrentCompanyMetric::qualityScore).toList()),
                captured == null ? null : captured.averageRotationScore(),
                captured == null ? null : captured.topSector()
        );
    }

    private static Integer averageInteger(List<Integer> values) {
        var available = values.stream().filter(java.util.Objects::nonNull).mapToInt(Integer::intValue).toArray();
        if (available.length == 0) return null;
        return (int) Math.round(java.util.Arrays.stream(available).average().orElse(0));
    }

    private static double rank(CompanyItem value, String sort) {
        return switch (sort) {
            case "growth" -> nullable(value.revenueGrowthYoY(), -999);
            case "margin" -> nullable(value.operatingMargin(), -999);
            case "valuation" -> -nullable(value.evToSales(), 999);
            case "marketcap" -> nullable(value.marketCap(), -1);
            case "priority" -> nullable(value.buyScore(), -1) * .45
                    + nullable(value.totalScore(), -1) * .35
                    + Math.min(100, Math.max(0,
                    Math.log10(Math.max(1, nullable(value.marketCap(), 1))) * 8)) * .20;
            default -> nullable(value.buyScore(), -1);
        };
    }

    private static double nullable(Number value, double fallback) {
        return value == null ? fallback : value.doubleValue();
    }

    private static String normalize(String ticker) {
        return ticker.toUpperCase(Locale.ROOT).replace('.', '-');
    }

    private static String bottomState(String value) {
        if (value == null) return null;
        return switch (value) {
            case "CONVICTION" -> "확신";
            case "CANDIDATE" -> "후보";
            case "UNMET" -> "미충족";
            default -> value;
        };
    }
}
