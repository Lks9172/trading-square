package io.macrosquare.company.application.service;

import io.macrosquare.company.application.model.CompanyReadModels.ArrayValue;
import io.macrosquare.company.application.model.CompanyReadModels.NullValue;
import io.macrosquare.company.application.model.CompanyReadModels.NumberValue;
import io.macrosquare.company.application.model.CompanyReadModels.ObjectValue;
import io.macrosquare.company.application.model.CompanyReadModels.Research;
import io.macrosquare.company.application.model.CompanyReadModels.TextValue;
import io.macrosquare.company.application.model.CompanyMarketQuote;
import io.macrosquare.company.domain.model.CompanyAnalystConsensus;
import io.macrosquare.company.domain.model.CompanyBuyLabel;
import io.macrosquare.company.domain.model.CompanyBuyScore;
import io.macrosquare.company.domain.model.CompanyFundamentalsSnapshot;
import io.macrosquare.company.domain.model.CompanyMarketExpectations;
import io.macrosquare.company.domain.model.CompanyScore;
import io.macrosquare.company.domain.model.ScoreBreakdown;
import io.macrosquare.company.domain.model.Ticker;

import java.util.List;

/**
 * Anti-corruption projection from the legacy company document to domain inputs.
 * The application layer only sees transport-neutral structured values.
 */
record CompanyResearchCoreProjection(
        String ticker,
        String cik,
        CompanyMarketQuote quote,
        CompanyFundamentalsSnapshot fundamentals,
        CompanyAnalystConsensus analystConsensus,
        CompanyMarketExpectations expectations,
        CompanyScore score,
        CompanyBuyScore buyScore
) {
    static CompanyResearchCoreProjection from(Research research) {
        var ticker = requiredText(research.profile(), "ticker");
        var cik = requiredText(research.profile(), "cik");
        var quote = quote(research.quote());
        var financials = research.financials();
        var financialTicker = requiredText(financials, "ticker");
        var financialCik = requiredText(financials, "cik");
        if (!ticker.equals(financialTicker)) throw new IllegalArgumentException("profile and financial ticker differ");
        if (!cik.equals(financialCik)) throw new IllegalArgumentException("profile and financial CIK differ");

        var snapshot = new CompanyFundamentalsSnapshot(
                new Ticker(financialTicker),
                financialCik,
                requiredText(financials, "asOf"),
                nullableNumber(financials, "revenueTtm"),
                nullableNumber(financials, "operatingIncomeTtm"),
                nullableNumber(financials, "netIncomeTtm"),
                nullableNumber(financials, "freeCashFlowTtm"),
                nullableNumber(financials, "cash"),
                nullableNumber(financials, "debt"),
                nullableNumber(financials, "currentAssets"),
                nullableNumber(financials, "currentLiabilities"),
                nullableNumber(financials, "receivables"),
                nullableNumber(financials, "inventory"),
                nullableNumber(financials, "capexTtm"),
                nullableNumber(financials, "operatingCashFlowTtm"),
                nullableNumber(financials, "sharesOutstanding"),
                nullableNumber(financials, "marketCap"),
                nullableNumber(financials, "enterpriseValue"),
                nullableNumber(financials, "revenueGrowthYoY"),
                nullableNumber(financials, "operatingMargin"),
                nullableNumber(financials, "operatingMarginTrend"),
                nullableNumber(financials, "freeCashFlowMargin"),
                nullableNumber(financials, "netDebtToRevenue"),
                nullableNumber(financials, "evToSales"),
                nullableNumber(financials, "evToFcf"),
                nullableNumber(financials, "shareDilutionYoY"),
                nullableNumber(financials, "stockCompToRevenue"),
                nullableNumber(financials, "roe"),
                nullableNumber(financials, "currentRatio"),
                nullableNumber(financials, "receivablesToRevenue"),
                nullableNumber(financials, "inventoryToRevenue"),
                optionalNumber(financials, "roic"),
                optionalNumber(financials, "effectiveTaxRate"),
                nullableBoolean(financials, "roicEstimated"),
                optionalNumber(financials, "shareDilution3yCagr"),
                optionalNumber(financials, "accrualRatio")
        );
        var estimateUpsidePct = nullableNumber(financials, "estimateUpsidePct");
        var analystConsensus = new CompanyAnalystConsensus(
                nullableNumber(financials, "analystScore"),
                estimateUpsidePct
        );
        // The legacy seed derived this value by subtracting target-price
        // upside snapshots. Preserve it only as target-upside movement; it is
        // not evidence that analysts revised forward EPS.
        var expectations = new CompanyMarketExpectations(
                estimateUpsidePct,
                nullableNumber(financials, "estimateRevision30d"),
                nullableNumber(financials, "analystScoreRevision30d")
        );
        var legacyScore = score(research.score(), snapshot.ticker());
        var legacyBuyScore = buyScore(research.buyScore());
        return new CompanyResearchCoreProjection(
                ticker,
                cik,
                quote,
                snapshot,
                analystConsensus,
                expectations,
                legacyScore,
                legacyBuyScore
        );
    }

    private static CompanyMarketQuote quote(ObjectValue value) {
        var price = nullableNumber(value, "price");
        var date = nullableText(value, "date");
        try {
            return new CompanyMarketQuote(
                    requiredText(value, "symbol"),
                    price,
                    date == null ? null : java.time.LocalDate.parse(date)
            );
        } catch (java.time.format.DateTimeParseException error) {
            throw new IllegalArgumentException("quote date must be an ISO local date", error);
        }
    }

    private static CompanyScore score(ObjectValue value, Ticker ticker) {
        var scoreTicker = requiredText(value, "ticker");
        if (!ticker.value().equals(scoreTicker)) throw new IllegalArgumentException("financial and score ticker differ");
        return new CompanyScore(
                ticker,
                requiredInteger(value, "totalScore"),
                scoreBreakdown(requiredObject(value, "growth")),
                scoreBreakdown(requiredObject(value, "quality")),
                scoreBreakdown(requiredObject(value, "valuation")),
                scoreBreakdown(requiredObject(value, "balanceSheet")),
                textList(value, "reasons")
        );
    }

    private static ScoreBreakdown scoreBreakdown(ObjectValue value) {
        return new ScoreBreakdown(requiredInteger(value, "value"), textList(value, "reasons"));
    }

    private static CompanyBuyScore buyScore(ObjectValue value) {
        return new CompanyBuyScore(
                requiredInteger(value, "appealScore"),
                requiredInteger(value, "crowdingScore"),
                requiredInteger(value, "buyScore"),
                switch (requiredText(value, "label")) {
                    case "매수 우호" -> CompanyBuyLabel.FAVORABLE;
                    case "선별 접근" -> CompanyBuyLabel.SELECTIVE;
                    case "추격 주의" -> CompanyBuyLabel.CHASE_RISK;
                    default -> throw new IllegalArgumentException("unsupported legacy buy label");
                },
                textList(value, "reasons")
        );
    }

    private static String requiredText(ObjectValue object, String field) {
        var value = required(object, field);
        if (value instanceof TextValue text && !text.value().isBlank()) return text.value();
        throw new IllegalArgumentException(field + " must be non-blank text");
    }

    private static Double nullableNumber(ObjectValue object, String field) {
        var value = required(object, field);
        if (value == NullValue.INSTANCE) return null;
        if (value instanceof NumberValue number) {
            var converted = number.value().doubleValue();
            if (Double.isFinite(converted)) return converted;
        }
        throw new IllegalArgumentException(field + " must be a finite number or null");
    }

    private static Double optionalNumber(ObjectValue object, String field) {
        var value = object.fields().get(field);
        if (value == null || value == NullValue.INSTANCE) return null;
        if (value instanceof NumberValue number) {
            var converted = number.value().doubleValue();
            if (Double.isFinite(converted)) return converted;
        }
        throw new IllegalArgumentException(field + " must be a finite number or null");
    }

    private static String nullableText(ObjectValue object, String field) {
        var value = required(object, field);
        if (value == NullValue.INSTANCE) return null;
        if (value instanceof TextValue text && !text.value().isBlank()) return text.value();
        throw new IllegalArgumentException(field + " must be non-blank text or null");
    }

    private static boolean nullableBoolean(ObjectValue object, String field) {
        var value = object.fields().get(field);
        if (value == null || value == NullValue.INSTANCE) return false;
        if (value instanceof io.macrosquare.company.application.model.CompanyReadModels.BooleanValue bool) {
            return bool.value();
        }
        throw new IllegalArgumentException(field + " must be boolean or null");
    }

    private static int requiredInteger(ObjectValue object, String field) {
        var value = required(object, field);
        if (value instanceof NumberValue number) {
            var converted = number.value().doubleValue();
            if (converted == Math.rint(converted)
                    && converted >= Integer.MIN_VALUE
                    && converted <= Integer.MAX_VALUE) return (int) converted;
        }
        throw new IllegalArgumentException(field + " must be an integer");
    }

    private static ObjectValue requiredObject(ObjectValue object, String field) {
        var value = required(object, field);
        if (value instanceof ObjectValue nested) return nested;
        throw new IllegalArgumentException(field + " must be an object");
    }

    private static List<String> textList(ObjectValue object, String field) {
        var value = required(object, field);
        if (!(value instanceof ArrayValue array)) throw new IllegalArgumentException(field + " must be an array");
        return array.values().stream().map(item -> {
            if (item instanceof TextValue text) return text.value();
            throw new IllegalArgumentException(field + " must contain text only");
        }).toList();
    }

    private static io.macrosquare.company.application.model.CompanyReadModels.StructuredValue required(
            ObjectValue object,
            String field
    ) {
        var value = object.fields().get(field);
        if (value == null) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
