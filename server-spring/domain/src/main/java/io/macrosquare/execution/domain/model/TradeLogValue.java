package io.macrosquare.execution.domain.model;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public sealed interface TradeLogValue permits TradeLogValue.TextValue, TradeLogValue.NumberValue,
        TradeLogValue.BooleanValue, TradeLogValue.ObjectValue, TradeLogValue.ArrayValue, TradeLogValue.NullValue {

    record TextValue(String value) implements TradeLogValue {
        public TextValue {
            value = Objects.requireNonNull(value, "value");
            if (value.length() > 8_000) throw new IllegalArgumentException("trade log context text is too long");
        }
    }

    record NumberValue(Number value) implements TradeLogValue {
        public NumberValue {
            value = Objects.requireNonNull(value, "value");
            if (!(value instanceof Long) && !(value instanceof BigDecimal)) {
                throw new IllegalArgumentException("trade log number must be Long or BigDecimal");
            }
        }
    }

    record BooleanValue(boolean value) implements TradeLogValue {
    }

    record ObjectValue(Map<String, TradeLogValue> fields) implements TradeLogValue {
        public ObjectValue {
            Objects.requireNonNull(fields, "fields");
            if (fields.size() > 128) throw new IllegalArgumentException("trade log object has too many fields");
            var copy = new LinkedHashMap<String, TradeLogValue>();
            fields.forEach((key, value) -> {
                if (key == null || key.isBlank() || key.length() > 128) {
                    throw new IllegalArgumentException("trade log context key is invalid");
                }
                copy.put(key, Objects.requireNonNull(value, "trade log context value"));
            });
            fields = Collections.unmodifiableMap(copy);
        }
    }

    record ArrayValue(List<TradeLogValue> values) implements TradeLogValue {
        public ArrayValue {
            values = List.copyOf(Objects.requireNonNull(values, "values"));
            if (values.size() > 256) throw new IllegalArgumentException("trade log array has too many items");
        }
    }

    enum NullValue implements TradeLogValue {
        INSTANCE
    }
}
