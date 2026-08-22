package io.macrosquare.market.application.port.out;

import io.macrosquare.market.application.model.MarketReadModels.Document;

import java.util.List;

public interface LoadMarketReadPort {

    Document loadLatestSnapshot();

    Document loadHistoryCoverage();

    Document loadHistory(String source, String key);

    Document loadHistorySeries(List<String> keys, String range, String interval);
}
