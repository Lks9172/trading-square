package io.macrosquare.market.adapter.out.persistence;

import io.macrosquare.market.adapter.out.json.MarketReadJsonMapper;
import io.macrosquare.market.application.model.MarketReadModels.ArrayValue;
import io.macrosquare.market.application.model.MarketReadModels.BooleanValue;
import io.macrosquare.market.application.model.MarketReadModels.Document;
import io.macrosquare.market.application.model.MarketReadModels.NullValue;
import io.macrosquare.market.application.model.MarketReadModels.NumberValue;
import io.macrosquare.market.application.model.MarketReadModels.ObjectValue;
import io.macrosquare.market.application.model.MarketReadModels.StructuredValue;
import io.macrosquare.market.application.model.MarketReadModels.TextValue;
import io.macrosquare.market.application.port.out.LoadMarketSnapshotProjectionPort;
import io.macrosquare.market.application.port.out.MarketReadUnavailableException;
import io.macrosquare.market.application.port.out.SaveMarketSnapshotProjectionPort;
import io.macrosquare.shared.adapter.out.storage.WritableJsonEnvelopeStore;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Objects;

/** Current market snapshot body in MinIO; object version metadata is recorded in PostgreSQL. */
public final class ObjectMarketSnapshotProjectionAdapter
        implements LoadMarketSnapshotProjectionPort, SaveMarketSnapshotProjectionPort {

    public static final String FILE_NAME = "latest-system-snapshot-default-v1.json";

    private final WritableJsonEnvelopeStore store;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ObjectMarketSnapshotProjectionAdapter(
            WritableJsonEnvelopeStore store,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.store = Objects.requireNonNull(store);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Document loadCurrentOrSeed() {
        try {
            return MarketReadJsonMapper.mapSnapshot(store.findValue(FILE_NAME).orElseThrow(() ->
                    new MarketReadUnavailableException("Market snapshot object is unavailable")));
        } catch (MarketReadUnavailableException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new MarketReadUnavailableException("Unable to load the MinIO market snapshot", error);
        }
    }

    @Override
    public void save(Document snapshot) {
        Objects.requireNonNull(snapshot);
        try {
            var envelope = objectMapper.createObjectNode();
            envelope.put("schemaVersion", 1);
            envelope.put("key", "latest-system-snapshot-default-v1");
            envelope.put("updatedAt", clock.instant().toString());
            envelope.set("value", objectMapper.valueToTree(plain(snapshot.root())));
            store.saveEnvelope(FILE_NAME, objectMapper.writeValueAsBytes(envelope), "application/json");
        } catch (RuntimeException error) {
            throw new MarketReadUnavailableException("Unable to persist the MinIO market snapshot", error);
        }
    }

    private static Object plain(StructuredValue value) {
        return switch (value) {
            case NullValue ignored -> null;
            case TextValue text -> text.value();
            case NumberValue number -> number.value();
            case BooleanValue bool -> bool.value();
            case ArrayValue array -> {
                var values = new ArrayList<>(array.values().size());
                array.values().forEach(item -> values.add(plain(item)));
                yield values;
            }
            case ObjectValue object -> {
                var fields = new LinkedHashMap<String, Object>(object.fields().size());
                object.fields().forEach((key, item) -> fields.put(key, plain(item)));
                yield fields;
            }
        };
    }
}
