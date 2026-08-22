package io.macrosquare.market.application.service;

import io.macrosquare.market.application.model.MarketReadModels.BooleanValue;
import io.macrosquare.market.application.model.MarketReadModels.NumberValue;
import io.macrosquare.market.application.model.MarketReadModels.ObjectValue;
import io.macrosquare.market.application.model.MarketReadModels.StructuredValue;
import io.macrosquare.market.application.model.MarketReadModels.TextValue;
import io.macrosquare.market.domain.observation.MarketInputFreshnessPolicy;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/** Resolves a persisted smart-money observation without letting stale evidence affect decisions. */
final class SmartMoneyFreshnessResolver {

    private static final String FRESHNESS_KEY = "SMART_MONEY_SCORE";

    private SmartMoneyFreshnessResolver() {
    }

    static Assessment resolve(
            Map<String, StructuredValue> smartMoney,
            LocalDate asOf,
            MarketInputFreshnessPolicy freshnessPolicy
    ) {
        var observedOn = date(smartMoney.get("lastUpdated"));
        var score = number(smartMoney.get("score"));
        var eligible = score != null && Double.isFinite(score)
                && freshnessPolicy.usableRaw(FRESHNESS_KEY, observedOn, asOf);
        var ageDays = observedOn == null || asOf == null || observedOn.isAfter(asOf)
                ? null : Math.toIntExact(ChronoUnit.DAYS.between(observedOn, asOf));
        return new Assessment(
                eligible ? score : 0d,
                score,
                observedOn,
                ageDays,
                freshnessPolicy.maximumRawAgeDays(FRESHNESS_KEY),
                eligible
        );
    }

    static ObjectValue metadata(Assessment assessment) {
        var fields = new LinkedHashMap<String, StructuredValue>();
        fields.put("observedOn", assessment.observedOn() == null
                ? io.macrosquare.market.application.model.MarketReadModels.NullValue.INSTANCE
                : new TextValue(assessment.observedOn().toString()));
        fields.put("ageDays", assessment.ageDays() == null
                ? io.macrosquare.market.application.model.MarketReadModels.NullValue.INSTANCE
                : new NumberValue((long) assessment.ageDays()));
        fields.put("maximumAgeDays", new NumberValue((long) assessment.maximumAgeDays()));
        fields.put("eligibleForRegime", new BooleanValue(assessment.eligibleForRegime()));
        fields.put("scoreApplied", new NumberValue(java.math.BigDecimal.valueOf(assessment.scoreForDecision())));
        fields.put("reason", new TextValue(assessment.eligibleForRegime()
                ? "최신성 기준 내 smart-money 관측값"
                : "최신성 기준 초과 또는 날짜 누락으로 거시 점수에서 중립 처리"));
        return new ObjectValue(fields);
    }

    private static Double number(StructuredValue value) {
        return value instanceof NumberValue number ? number.value().doubleValue() : null;
    }

    private static LocalDate date(StructuredValue value) {
        if (!(value instanceof TextValue text)) return null;
        try {
            return LocalDate.parse(text.value());
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    record Assessment(
            double scoreForDecision,
            Double observedScore,
            LocalDate observedOn,
            Integer ageDays,
            int maximumAgeDays,
            boolean eligibleForRegime
    ) {
    }
}
