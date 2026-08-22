package io.macrosquare.research.adapter.out.company;

import io.macrosquare.company.application.port.out.LoadCompanyPriceHistoryPort;
import io.macrosquare.research.application.port.out.LoadSectorConstituentPriceHistoryPort;
import io.macrosquare.research.domain.rotation.SectorConstituentPriceSeries;
import io.macrosquare.research.domain.rotation.SectorPricePoint;

import java.util.Objects;

/** Outer-layer ACL translating Company price-history types into Research types. */
public final class CompanyPriceHistoryResearchAdapter implements LoadSectorConstituentPriceHistoryPort {

    private final LoadCompanyPriceHistoryPort delegate;

    public CompanyPriceHistoryResearchAdapter(LoadCompanyPriceHistoryPort delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public SectorConstituentPriceSeries load(String normalizedTicker) {
        return new SectorConstituentPriceSeries(normalizedTicker, delegate.load(normalizedTicker).stream()
                .map(value -> new SectorPricePoint(value.date(), value.close()))
                .toList());
    }
}
