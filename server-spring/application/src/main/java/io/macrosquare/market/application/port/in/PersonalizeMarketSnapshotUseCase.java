package io.macrosquare.market.application.port.in;

import io.macrosquare.market.application.model.MarketReadModels.Document;

public interface PersonalizeMarketSnapshotUseCase {
    Document personalize(Document profileOverrides);
}
