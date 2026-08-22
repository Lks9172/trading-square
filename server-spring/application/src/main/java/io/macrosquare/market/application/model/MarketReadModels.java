package io.macrosquare.market.application.model;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Transport-neutral projections for the snapshot and history read slice.
 *
 * <p>The legacy contract is a large, evolving JSON document. Keeping its tree
 * in application-owned immutable values lets the anti-corruption adapter
 * validate and copy the document without exposing Jackson, HTTP, controller,
 * cache, or persistence types across the application boundary.</p>
 */
public final class MarketReadModels {

    private MarketReadModels() {
    }

    public record Document(ObjectValue root) {
        public Document {
            root = Objects.requireNonNull(root, "root");
        }
    }

    public sealed interface StructuredValue permits
            ObjectValue, ArrayValue, TextValue, NumberValue, BooleanValue, NullValue {
    }

    public record ObjectValue(Map<String, StructuredValue> fields) implements StructuredValue {
        public ObjectValue {
            Objects.requireNonNull(fields, "fields");
            var ordered = new LinkedHashMap<String, StructuredValue>(fields.size());
            fields.forEach((key, value) -> ordered.put(
                    Objects.requireNonNull(key, "object field name"),
                    Objects.requireNonNull(value, "object field value")
            ));
            fields = Collections.unmodifiableMap(ordered);
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
                throw new IllegalArgumentException("number value must be Long or BigDecimal");
            }
        }
    }

    public record BooleanValue(boolean value) implements StructuredValue {
    }

    public enum NullValue implements StructuredValue {
        INSTANCE
    }

    public static Document document(Map<String, StructuredValue> fields) {
        return new Document(new ObjectValue(fields));
    }
}
