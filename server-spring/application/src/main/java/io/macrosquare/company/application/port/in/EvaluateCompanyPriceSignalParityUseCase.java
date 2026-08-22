package io.macrosquare.company.application.port.in;

@FunctionalInterface
public interface EvaluateCompanyPriceSignalParityUseCase {

    CompanyPriceSignalParityReport evaluate(String ticker);

    /**
     * Calculates only the current price, volume, bottom, and reversal evidence.
     *
     * <p>Background list projections do not consume the five-year walk-forward
     * validation or the legacy migration comparison. Implementations may skip
     * those expensive diagnostics while preserving exactly the same current
     * signal policies. The default keeps lightweight test doubles and alternate
     * adapters backward compatible.</p>
     */
    default CompanyPriceSignalParityReport evaluateCurrent(String ticker) {
        return evaluate(ticker);
    }
}
