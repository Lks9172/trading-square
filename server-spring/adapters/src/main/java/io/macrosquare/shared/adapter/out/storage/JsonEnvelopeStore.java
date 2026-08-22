package io.macrosquare.shared.adapter.out.storage;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Optional;

/** Adapter-local SPI for bounded {@code {value: ...}} projection documents. */
public interface JsonEnvelopeStore {

    Optional<JsonNode> findValue(String fileName);

    default List<NamedValue> listValues(String prefix, int limit) {
        return List.of();
    }

    default Optional<String> findText(String fileName, long maximumBytes) {
        return Optional.empty();
    }

    record NamedValue(String name, JsonNode value) {
    }
}
