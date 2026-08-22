package io.macrosquare.company.adapter.out.persistence;

import io.macrosquare.company.application.model.CompanyReadModels.ArrayValue;
import io.macrosquare.company.application.model.CompanyReadModels.NullValue;
import io.macrosquare.company.application.model.CompanyReadModels.ObjectValue;
import io.macrosquare.company.application.model.CompanyReadModels.Research;
import io.macrosquare.company.application.model.CompanyReadModels.SearchItem;
import io.macrosquare.company.application.model.CompanyReadModels.StructuredValue;
import io.macrosquare.company.application.model.CompanyReadModels.TextValue;
import io.macrosquare.shared.adapter.out.catalog.CurrentResearchUniverseTickerRegistry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds an identity-only, fail-closed seed for a newly added current company. */
final class CurrentCompanyResearchSeedFactory {

    private CurrentCompanyResearchSeedFactory() {
    }

    static Research identityOnly(SearchItem identity) {
        var profile = new LinkedHashMap<String, StructuredValue>();
        profile.put("ticker", text(identity.ticker()));
        profile.put("name", text(identity.title()));
        profile.put("cik", text(identity.cik()));
        var replacement = CurrentResearchUniverseTickerRegistry
                .replacementByTicker(identity.ticker())
                .orElseThrow(() -> new IllegalArgumentException("replacement identity is required"));
        profile.put("exchange", text(replacement.exchange()));
        profile.put("sic", text(replacement.sic()));
        var quote = new ObjectValue(Map.of("symbol", text(identity.ticker())));
        var financials = new ObjectValue(Map.of("ticker", text(identity.ticker())));
        var score = new ObjectValue(Map.of("ticker", text(identity.ticker())));
        var emptyObject = new ObjectValue(Map.of());
        var emptyArray = new ArrayValue(List.of());
        return new Research(
                new ObjectValue(profile), quote, financials, score, emptyObject,
                emptyArray, emptyArray, emptyArray,
                NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE,
                NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE,
                NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE,
                NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE,
                NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE,
                NullValue.INSTANCE, emptyArray
        );
    }

    private static TextValue text(String value) {
        return new TextValue(value);
    }
}
