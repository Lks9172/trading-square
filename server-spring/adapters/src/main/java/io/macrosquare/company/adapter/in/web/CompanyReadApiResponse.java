package io.macrosquare.company.adapter.in.web;

import io.macrosquare.company.application.model.CompanyReadModels;
import io.macrosquare.company.application.model.CompanyReadModels.ArrayValue;
import io.macrosquare.company.application.model.CompanyReadModels.BooleanValue;
import io.macrosquare.company.application.model.CompanyReadModels.NullValue;
import io.macrosquare.company.application.model.CompanyReadModels.NumberValue;
import io.macrosquare.company.application.model.CompanyReadModels.ObjectValue;
import io.macrosquare.company.application.model.CompanyReadModels.StructuredValue;
import io.macrosquare.company.application.model.CompanyReadModels.TextValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public final class CompanyReadApiResponse {

    private CompanyReadApiResponse() {
    }

    public record Search(List<SearchItem> items) {
        static Search from(CompanyReadModels.SearchResult source) {
            return new Search(source.items().stream().map(SearchItem::from).toList());
        }
    }

    public record SearchItem(String ticker, String cik, String title) {
        static SearchItem from(CompanyReadModels.SearchItem source) {
            return new SearchItem(source.ticker(), source.cik(), source.title());
        }
    }

    public record Summaries(List<Summary> items) {
        static Summaries from(CompanyReadModels.SummaryResult source) {
            return new Summaries(source.items().stream().map(Summary::from).toList());
        }
    }

    public record Summary(
            String ticker,
            String name,
            Integer totalScore,
            Integer buyScore,
            String buyLabel,
            Number revenueGrowthYoY,
            Number operatingMargin,
            Number evToSales,
            Integer crowdingScore,
            Integer appealScore,
            String bottomState,
            Integer earningsBottomScore,
            Integer priceBottomScore,
            Integer volumeConfirmationScore,
            Integer failureRiskScore
    ) {
        static Summary from(CompanyReadModels.Summary source) {
            return new Summary(
                    source.ticker(),
                    source.name(),
                    source.totalScore(),
                    source.buyScore(),
                    source.buyLabel(),
                    source.revenueGrowthYoY(),
                    source.operatingMargin(),
                    source.evToSales(),
                    source.crowdingScore(),
                    source.appealScore(),
                    source.bottomState(),
                    source.earningsBottomScore(),
                    source.priceBottomScore(),
                    source.volumeConfirmationScore(),
                    source.failureRiskScore()
            );
        }
    }

    public record Research(
            Object profile,
            Object quote,
            Object financials,
            Object score,
            Object buyScore,
            Object filings,
            Object irMaterials,
            Object highlights,
            Object peerGroup,
            Object bottleneck,
            Object narrative,
            Object capitalFlow,
            Object cashFlowQuality,
            Object multipleInsight,
            Object guidanceInsight,
            Object timeframeView,
            Object correctionAssessment,
            Object thesisMonitor,
            Object reversalConfirmation,
            Object sectorContext,
            Object verdicts,
            Object bottomSignal,
            Object positionSizing,
            Object executionBridge,
            Object peers
    ) {
        static Research from(CompanyReadModels.Research source) {
            return new Research(
                    webValue(source.profile()),
                    webValue(source.quote()),
                    webValue(source.financials()),
                    webValue(source.score()),
                    webValue(source.buyScore()),
                    webValue(source.filings()),
                    webValue(source.irMaterials()),
                    webValue(source.highlights()),
                    webValue(source.peerGroup()),
                    webValue(source.bottleneck()),
                    webValue(source.narrative()),
                    webValue(source.capitalFlow()),
                    webValue(source.cashFlowQuality()),
                    webValue(source.multipleInsight()),
                    webValue(source.guidanceInsight()),
                    webValue(source.timeframeView()),
                    webValue(source.correctionAssessment()),
                    webValue(source.thesisMonitor()),
                    webValue(source.reversalConfirmation()),
                    webValue(source.sectorContext()),
                    webValue(source.verdicts()),
                    webValue(source.bottomSignal()),
                    webValue(source.positionSizing()),
                    webValue(source.executionBridge()),
                    webValue(source.peers())
            );
        }
    }

    private static Object webValue(StructuredValue source) {
        return switch (source) {
            case NullValue ignored -> null;
            case TextValue text -> text.value();
            case NumberValue number -> number.value();
            case BooleanValue bool -> bool.value();
            case ArrayValue array -> {
                var values = new ArrayList<>(array.values().size());
                array.values().forEach(value -> values.add(webValue(value)));
                yield values;
            }
            case ObjectValue object -> {
                var fields = new LinkedHashMap<String, Object>(object.fields().size());
                object.fields().forEach((key, value) -> fields.put(key, webValue(value)));
                yield fields;
            }
        };
    }
}
