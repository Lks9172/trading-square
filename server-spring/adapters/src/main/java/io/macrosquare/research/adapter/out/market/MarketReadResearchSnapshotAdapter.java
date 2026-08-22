package io.macrosquare.research.adapter.out.market;

import io.macrosquare.market.application.model.MarketReadModels.ArrayValue;
import io.macrosquare.market.application.model.MarketReadModels.BooleanValue;
import io.macrosquare.market.application.model.MarketReadModels.NullValue;
import io.macrosquare.market.application.model.MarketReadModels.NumberValue;
import io.macrosquare.market.application.model.MarketReadModels.ObjectValue;
import io.macrosquare.market.application.model.MarketReadModels.StructuredValue;
import io.macrosquare.market.application.model.MarketReadModels.TextValue;
import io.macrosquare.market.application.port.out.LoadMarketReadPort;
import io.macrosquare.research.adapter.out.json.ResearchSnapshotJsonMapper;
import io.macrosquare.research.application.model.ResearchSnapshot;
import io.macrosquare.research.application.port.out.LoadResearchSnapshotPort;
import io.macrosquare.research.application.port.out.ResearchSnapshotUnavailableException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * Reuses the native market snapshot port instead of issuing a second request
 * to any retired transport API. The application boundary remains transport-neutral.
 */
public final class MarketReadResearchSnapshotAdapter implements LoadResearchSnapshotPort {

    private final LoadMarketReadPort marketReadPort;
    private final ObjectMapper objectMapper;

    public MarketReadResearchSnapshotAdapter(LoadMarketReadPort marketReadPort, ObjectMapper objectMapper) {
        this.marketReadPort = Objects.requireNonNull(marketReadPort);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public ResearchSnapshot loadLatest() {
        try {
            var document = marketReadPort.loadLatestSnapshot();
            var json = objectMapper.valueToTree(plainValue(document.root()));
            return ResearchSnapshotJsonMapper.map(json);
        } catch (ResearchSnapshotUnavailableException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new ResearchSnapshotUnavailableException(
                    "Unable to normalize the persisted market snapshot for research", error);
        }
    }

    private static Object plainValue(StructuredValue source) {
        return switch (source) {
            case NullValue ignored -> null;
            case TextValue text -> text.value();
            case NumberValue number -> number.value();
            case BooleanValue bool -> bool.value();
            case ArrayValue array -> {
                var values = new ArrayList<>(array.values().size());
                array.values().forEach(value -> values.add(plainValue(value)));
                yield values;
            }
            case ObjectValue object -> {
                var fields = new LinkedHashMap<String, Object>(object.fields().size());
                object.fields().forEach((key, value) -> fields.put(key, plainValue(value)));
                yield fields;
            }
        };
    }
}
