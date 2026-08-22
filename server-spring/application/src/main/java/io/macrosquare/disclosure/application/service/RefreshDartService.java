package io.macrosquare.disclosure.application.service;

import io.macrosquare.disclosure.application.model.DartRefreshReport;
import io.macrosquare.disclosure.application.port.in.RefreshDartUseCase;
import io.macrosquare.disclosure.application.port.out.CollectDartCompanyDirectoryPort;
import io.macrosquare.disclosure.application.port.out.CollectDartDisclosuresPort;
import io.macrosquare.disclosure.application.port.out.CollectDartFinancialsPort;
import io.macrosquare.disclosure.application.port.out.DartRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class RefreshDartService implements RefreshDartUseCase {

    private final CollectDartCompanyDirectoryPort directory;
    private final CollectDartDisclosuresPort disclosures;
    private final CollectDartFinancialsPort financials;
    private final DartRepository repository;
    private final List<String> stockCodes;
    private final Clock clock;
    private final int lookbackDays;
    private final Duration directoryRefreshTtl;

    public RefreshDartService(
            CollectDartCompanyDirectoryPort directory,
            CollectDartDisclosuresPort disclosures,
            CollectDartFinancialsPort financials,
            DartRepository repository,
            List<String> stockCodes,
            Clock clock,
            int lookbackDays
    ) {
        this(directory, disclosures, financials, repository, stockCodes, clock, lookbackDays,
                Duration.ofHours(24));
    }

    public RefreshDartService(
            CollectDartCompanyDirectoryPort directory,
            CollectDartDisclosuresPort disclosures,
            CollectDartFinancialsPort financials,
            DartRepository repository,
            List<String> stockCodes,
            Clock clock,
            int lookbackDays,
            Duration directoryRefreshTtl
    ) {
        this.directory = Objects.requireNonNull(directory);
        this.disclosures = Objects.requireNonNull(disclosures);
        this.financials = Objects.requireNonNull(financials);
        this.repository = Objects.requireNonNull(repository);
        this.stockCodes = List.copyOf(stockCodes);
        this.clock = Objects.requireNonNull(clock);
        if (lookbackDays < 1 || lookbackDays > 365) throw new IllegalArgumentException("lookbackDays is out of range");
        this.lookbackDays = lookbackDays;
        if (directoryRefreshTtl == null || directoryRefreshTtl.isZero() || directoryRefreshTtl.isNegative()) {
            throw new IllegalArgumentException("directoryRefreshTtl must be positive");
        }
        this.directoryRefreshTtl = directoryRefreshTtl;
    }

    @Override
    public DartRefreshReport refresh() {
        var started = clock.instant();
        var failures = new ArrayList<String>();
        var byStockCode = new java.util.LinkedHashMap<String, io.macrosquare.disclosure.domain.model.DartCompany>();
        stockCodes.forEach(stockCode -> {
            var existing = repository.findByStockCode(stockCode);
            if (existing != null) byStockCode.put(stockCode, existing);
        });
        var directoryUpdatedAt = repository.companyDirectoryUpdatedAt();
        var directoryStale = directoryUpdatedAt == null
                || directoryUpdatedAt.isBefore(started.minus(directoryRefreshTtl));
        var companyCount = 0;
        if (directoryStale || byStockCode.size() < stockCodes.stream().distinct().count()) {
            try {
                var companies = directory.collect();
                companyCount = repository.saveCompanies(companies, clock.instant());
                companies.stream().filter(value -> !value.stockCode().isBlank())
                        .forEach(value -> byStockCode.put(value.stockCode(), value));
            } catch (RuntimeException error) {
                failures.add("directory:" + error.getClass().getSimpleName());
            }
        }
        var now = LocalDate.ofInstant(started, ZoneOffset.UTC);
        var disclosureCount = 0;
        var financialCount = 0;
        for (var stockCode : stockCodes) {
            var company = byStockCode.get(stockCode);
            if (company == null) {
                failures.add(stockCode + ":CompanyNotFound");
                continue;
            }
            try {
                var values = disclosures.collect(company, now.minusDays(lookbackDays), now, 100);
                disclosureCount += repository.saveDisclosures(values, clock.instant());
            } catch (RuntimeException error) {
                failures.add(stockCode + ":disclosures:" + error.getClass().getSimpleName());
            }
            try {
                var found = false;
                RuntimeException lastFailure = null;
                for (var period : availablePeriods(now)) {
                    try {
                        var values = financials.collect(company, period.year(), period.reportCode());
                        if (values.isEmpty()) continue;
                        financialCount += repository.saveFinancials(values, clock.instant());
                        found = true;
                        break;
                    } catch (RuntimeException error) {
                        lastFailure = error;
                    }
                }
                if (!found) {
                    var suffix = lastFailure == null ? "DataUnavailable" : lastFailure.getClass().getSimpleName();
                    failures.add(stockCode + ":financials:" + suffix);
                }
            } catch (RuntimeException error) {
                failures.add(stockCode + ":financials:" + error.getClass().getSimpleName());
            }
        }
        return new DartRefreshReport(
                started, clock.instant(), companyCount, disclosureCount, financialCount, failures);
    }

    static FinancialPeriod latestAvailablePeriod(LocalDate date) {
        return availablePeriods(date).getFirst();
    }

    /** Conservative statutory availability dates, followed by progressively older fallbacks. */
    static List<FinancialPeriod> availablePeriods(LocalDate date) {
        Objects.requireNonNull(date, "date");
        var result = new ArrayList<FinancialPeriod>();
        var year = date.getYear();
        if (!date.isBefore(LocalDate.of(year, 11, 15))) {
            result.add(new FinancialPeriod(year, "11014"));
        }
        if (!date.isBefore(LocalDate.of(year, 8, 15))) {
            result.add(new FinancialPeriod(year, "11012"));
        }
        if (!date.isBefore(LocalDate.of(year, 5, 16))) {
            result.add(new FinancialPeriod(year, "11013"));
        }
        result.add(new FinancialPeriod(year - 1, "11011"));
        return List.copyOf(result);
    }

    record FinancialPeriod(int year, String reportCode) {
    }
}
