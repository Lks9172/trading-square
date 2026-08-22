package io.macrosquare.company.application.port.in;

public final class CompanyTickerNotFoundException extends RuntimeException {

    public CompanyTickerNotFoundException(String ticker) {
        super("SEC ticker mapping not found for " + ticker);
    }
}
