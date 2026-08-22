package io.macrosquare.research.application.model;

public record PeerUniverseCompany(String ticker, String cik, String companyName) {
    public PeerUniverseCompany {
        if (ticker == null || ticker.isBlank() || cik == null || cik.isBlank()
                || companyName == null || companyName.isBlank()) {
            throw new IllegalArgumentException("peer universe company is incomplete");
        }
    }
}
