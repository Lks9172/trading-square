package io.macrosquare.compatibility.application.model;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Transport-neutral values for routes awaiting native calculation ownership. */
public final class SupplementalApiModels {

    private SupplementalApiModels() {
    }

    public record Document(ObjectValue root) {
        public Document {
            root = Objects.requireNonNull(root, "root");
        }
    }

    public record TextPayload(String text) {
        public TextPayload {
            text = Objects.requireNonNull(text, "text");
            if (text.length() > 2_000_000) throw new IllegalArgumentException("text payload is too large");
        }
    }

    public sealed interface StructuredValue permits ObjectValue, ArrayValue, TextValue, NumberValue,
            BooleanValue, NullValue {
    }

    public record ObjectValue(Map<String, StructuredValue> fields) implements StructuredValue {
        public ObjectValue {
            Objects.requireNonNull(fields, "fields");
            var copy = new LinkedHashMap<String, StructuredValue>();
            fields.forEach((key, value) -> copy.put(
                    Objects.requireNonNull(key, "field name"),
                    Objects.requireNonNull(value, "field value")
            ));
            fields = Collections.unmodifiableMap(copy);
        }
    }

    public record ArrayValue(List<StructuredValue> values) implements StructuredValue {
        public ArrayValue {
            values = List.copyOf(Objects.requireNonNull(values, "values"));
        }
    }

    public record TextValue(String value) implements StructuredValue {
        public TextValue {
            value = Objects.requireNonNull(value, "value");
        }
    }

    public record NumberValue(Number value) implements StructuredValue {
        public NumberValue {
            value = Objects.requireNonNull(value, "value");
            if (!(value instanceof Long) && !(value instanceof BigDecimal)) {
                throw new IllegalArgumentException("number must be Long or BigDecimal");
            }
        }
    }

    public record BooleanValue(boolean value) implements StructuredValue {
    }

    public enum NullValue implements StructuredValue {
        INSTANCE
    }
}
