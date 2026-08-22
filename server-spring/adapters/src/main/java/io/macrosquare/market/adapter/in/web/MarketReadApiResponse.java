package io.macrosquare.market.adapter.in.web;

import io.macrosquare.market.application.model.MarketReadModels.ArrayValue;
import io.macrosquare.market.application.model.MarketReadModels.BooleanValue;
import io.macrosquare.market.application.model.MarketReadModels.Document;
import io.macrosquare.market.application.model.MarketReadModels.NullValue;
import io.macrosquare.market.application.model.MarketReadModels.NumberValue;
import io.macrosquare.market.application.model.MarketReadModels.ObjectValue;
import io.macrosquare.market.application.model.MarketReadModels.StructuredValue;
import io.macrosquare.market.application.model.MarketReadModels.TextValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;

final class MarketReadApiResponse {

    private MarketReadApiResponse() {
    }

    static Object from(Document source) {
        return webValue(source.root());
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
