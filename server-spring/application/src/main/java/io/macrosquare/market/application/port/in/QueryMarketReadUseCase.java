package io.macrosquare.market.application.port.in;

import io.macrosquare.market.application.model.MarketReadModels.Document;

import java.util.List;

public interface QueryMarketReadUseCase {

    Document latestSnapshot();

    Document historyCoverage();

    Document history(String source, String key);

    Document historySeries(List<String> keyParameters, String range, String interval);
}
