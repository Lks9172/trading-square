package io.macrosquare.company.adapter.out.persistence;

import io.macrosquare.company.application.model.CompanyResearchSummarySnapshot;
import io.macrosquare.company.application.model.CompanyMacdTimingSnapshot;
import io.macrosquare.company.application.port.out.CompanyResearchSummaryPersistenceException;
import io.macrosquare.company.application.port.out.CompanyResearchSummaryRepository;
import io.macrosquare.shared.adapter.out.persistence.PostgresTemporal;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** PostgreSQL read model for current company list metrics. */
public final class JdbcCompanyResearchSummaryRepository implements CompanyResearchSummaryRepository {

    /** Increment whenever persisted score/signal semantics change incompatibly. */
    static final int CURRENT_CALCULATION_VERSION = 6;
    private static final Pattern SAFE_TICKER = Pattern.compile("[A-Z0-9][A-Z0-9.-]{0,19}");
    private static final String SELECT = """
            select ticker, fundamentals_as_of, market_cap, revenue_growth_yoy, operating_margin,
                   ev_to_sales, total_score, growth_score, quality_score, valuation_score,
                   balance_sheet_score, buy_score, buy_label, appeal_score, crowding_score,
                   valuation_basis, valuation_eligible, valuation_warnings, fundamentals_status,
                   latest_periodic_report_date, latest_periodic_filing_date, latest_periodic_form,
                   fundamentals_lag_days, score_warnings, price_bottom_score,
                   volume_confirmation_score, failure_risk_score, confirmed_bottom_score,
                   confirmed_bottom_state, confirmed_bottom_signal_date, reversal_status,
                   reversal_score, price_signal_reasons, macd_timing, execution_action, updated_at
            from company.research_summary
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcCompanyResearchSummaryRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public Optional<CompanyResearchSummarySnapshot> find(String normalizedTicker) {
        try {
            var parameters = new MapSqlParameterSource()
                    .addValue("calculationVersion", CURRENT_CALCULATION_VERSION)
                    .addValue("ticker", normalize(normalizedTicker));
            var values = jdbc.query(SELECT + " where calculation_version = :calculationVersion and ticker = :ticker",
                    parameters, this::map);
            return values.stream().findFirst();
        } catch (RuntimeException error) {
            throw new CompanyResearchSummaryPersistenceException("Unable to load company summary", error);
        }
    }

    @Override
    public Optional<CompanyResearchSummarySnapshot> findHistoricalForQuarantine(String normalizedTicker) {
        try {
            var values = jdbc.query(SELECT + " where ticker = :ticker",
                    new MapSqlParameterSource("ticker", normalize(normalizedTicker)), this::map);
            return values.stream().findFirst();
        } catch (RuntimeException error) {
            throw new CompanyResearchSummaryPersistenceException(
                    "Unable to load historical company summary for quarantine", error);
        }
    }

    @Override
    public Map<String, CompanyResearchSummarySnapshot> findAll() {
        try {
            var result = new LinkedHashMap<String, CompanyResearchSummarySnapshot>();
            var parameters = new MapSqlParameterSource("calculationVersion", CURRENT_CALCULATION_VERSION);
            jdbc.query(SELECT + " where calculation_version = :calculationVersion order by ticker", parameters, row -> {
                var value = map(row, 0);
                result.put(value.ticker(), value);
            });
            return Map.copyOf(result);
        } catch (RuntimeException error) {
            throw new CompanyResearchSummaryPersistenceException("Unable to load company summaries", error);
        }
    }

    @Override
    public void save(CompanyResearchSummarySnapshot value) {
        Objects.requireNonNull(value, "snapshot");
        try {
            jdbc.update("""
                    insert into company.research_summary (
                        ticker, fundamentals_as_of, market_cap, revenue_growth_yoy, operating_margin,
                        ev_to_sales, total_score, growth_score, quality_score, valuation_score,
                        balance_sheet_score, buy_score, buy_label, appeal_score, crowding_score,
                        valuation_basis, valuation_eligible, valuation_warnings, fundamentals_status,
                        latest_periodic_report_date, latest_periodic_filing_date, latest_periodic_form,
                        fundamentals_lag_days, score_warnings, price_bottom_score,
                        volume_confirmation_score, failure_risk_score, confirmed_bottom_score,
                        confirmed_bottom_state, confirmed_bottom_signal_date, reversal_status,
                        reversal_score, price_signal_reasons, macd_timing, execution_action,
                        calculation_version, updated_at
                    ) values (
                        :ticker, :fundamentalsAsOf, :marketCap, :revenueGrowthYoY, :operatingMargin,
                        :evToSales, :totalScore, :growthScore, :qualityScore, :valuationScore,
                        :balanceSheetScore, :buyScore, :buyLabel, :appealScore, :crowdingScore,
                        :valuationBasis, :valuationEligible, cast(:valuationWarnings as jsonb), :fundamentalsStatus,
                        :latestPeriodicReportDate, :latestPeriodicFilingDate, :latestPeriodicForm,
                        :fundamentalsLagDays, cast(:scoreWarnings as jsonb), :priceBottomScore,
                        :volumeConfirmationScore, :failureRiskScore, :confirmedBottomScore,
                        :confirmedBottomState, :confirmedBottomSignalDate, :reversalStatus,
                        :reversalScore, cast(:priceSignalReasons as jsonb), cast(:macdTiming as jsonb), :executionAction,
                        :calculationVersion, :updatedAt
                    )
                    on conflict (ticker) do update set
                        fundamentals_as_of = excluded.fundamentals_as_of,
                        market_cap = excluded.market_cap,
                        revenue_growth_yoy = excluded.revenue_growth_yoy,
                        operating_margin = excluded.operating_margin,
                        ev_to_sales = excluded.ev_to_sales,
                        total_score = excluded.total_score,
                        growth_score = excluded.growth_score,
                        quality_score = excluded.quality_score,
                        valuation_score = excluded.valuation_score,
                        balance_sheet_score = excluded.balance_sheet_score,
                        buy_score = excluded.buy_score,
                        buy_label = excluded.buy_label,
                        appeal_score = excluded.appeal_score,
                        crowding_score = excluded.crowding_score,
                        valuation_basis = excluded.valuation_basis,
                        valuation_eligible = excluded.valuation_eligible,
                        valuation_warnings = excluded.valuation_warnings,
                        fundamentals_status = excluded.fundamentals_status,
                        latest_periodic_report_date = excluded.latest_periodic_report_date,
                        latest_periodic_filing_date = excluded.latest_periodic_filing_date,
                        latest_periodic_form = excluded.latest_periodic_form,
                        fundamentals_lag_days = excluded.fundamentals_lag_days,
                        score_warnings = excluded.score_warnings,
                        price_bottom_score = excluded.price_bottom_score,
                        volume_confirmation_score = excluded.volume_confirmation_score,
                        failure_risk_score = excluded.failure_risk_score,
                        confirmed_bottom_score = excluded.confirmed_bottom_score,
                        confirmed_bottom_state = excluded.confirmed_bottom_state,
                        confirmed_bottom_signal_date = excluded.confirmed_bottom_signal_date,
                        reversal_status = excluded.reversal_status,
                        reversal_score = excluded.reversal_score,
                        price_signal_reasons = excluded.price_signal_reasons,
                        macd_timing = excluded.macd_timing,
                        execution_action = excluded.execution_action,
                        calculation_version = excluded.calculation_version,
                        updated_at = excluded.updated_at
                    """, parameters(value));
        } catch (RuntimeException error) {
            throw new CompanyResearchSummaryPersistenceException("Unable to persist company summary", error);
        }
    }

    private MapSqlParameterSource parameters(CompanyResearchSummarySnapshot value) {
        try {
            return new MapSqlParameterSource()
                    .addValue("ticker", normalize(value.ticker()))
                    .addValue("fundamentalsAsOf", value.fundamentalsAsOf())
                    .addValue("marketCap", value.marketCap())
                    .addValue("revenueGrowthYoY", value.revenueGrowthYoY())
                    .addValue("operatingMargin", value.operatingMargin())
                    .addValue("evToSales", value.evToSales())
                    .addValue("totalScore", value.totalScore())
                    .addValue("growthScore", value.growthScore())
                    .addValue("qualityScore", value.qualityScore())
                    .addValue("valuationScore", value.valuationScore())
                    .addValue("balanceSheetScore", value.balanceSheetScore())
                    .addValue("buyScore", value.buyScore())
                    .addValue("buyLabel", value.buyLabel())
                    .addValue("appealScore", value.appealScore())
                    .addValue("crowdingScore", value.crowdingScore())
                    .addValue("valuationBasis", value.valuationBasis())
                    .addValue("valuationEligible", value.valuationEligible())
                    .addValue("valuationWarnings", objectMapper.writeValueAsString(value.valuationWarnings()))
                    .addValue("fundamentalsStatus", value.fundamentalsStatus())
                    .addValue("latestPeriodicReportDate", value.latestPeriodicReportDate())
                    .addValue("latestPeriodicFilingDate", value.latestPeriodicFilingDate())
                    .addValue("latestPeriodicForm", value.latestPeriodicForm())
                    .addValue("fundamentalsLagDays", value.fundamentalsLagDays())
                    .addValue("scoreWarnings", objectMapper.writeValueAsString(value.scoreWarnings()))
                    .addValue("priceBottomScore", value.priceBottomScore())
                    .addValue("volumeConfirmationScore", value.volumeConfirmationScore())
                    .addValue("failureRiskScore", value.failureRiskScore())
                    .addValue("confirmedBottomScore", value.confirmedBottomScore())
                    .addValue("confirmedBottomState", value.confirmedBottomState())
                    .addValue("confirmedBottomSignalDate", value.confirmedBottomSignalDate())
                    .addValue("reversalStatus", value.reversalStatus())
                    .addValue("reversalScore", value.reversalScore())
                    .addValue("priceSignalReasons", objectMapper.writeValueAsString(value.priceSignalReasons()))
                    .addValue("macdTiming", value.macdTiming() == null
                            ? null : objectMapper.writeValueAsString(value.macdTiming()))
                    .addValue("executionAction", value.executionAction())
                    .addValue("calculationVersion", CURRENT_CALCULATION_VERSION)
                    .addValue("updatedAt", PostgresTemporal.timestamp(value.updatedAt()));
        } catch (RuntimeException error) {
            throw new CompanyResearchSummaryPersistenceException("Unable to encode company summary", error);
        }
    }

    private CompanyResearchSummarySnapshot map(ResultSet row, int ignored) throws SQLException {
        return new CompanyResearchSummarySnapshot(
                row.getString("ticker"),
                row.getObject("fundamentals_as_of", java.time.LocalDate.class),
                nullableDouble(row, "market_cap"),
                nullableDouble(row, "revenue_growth_yoy"),
                nullableDouble(row, "operating_margin"),
                nullableDouble(row, "ev_to_sales"),
                nullableInteger(row, "total_score"),
                nullableInteger(row, "growth_score"),
                nullableInteger(row, "quality_score"),
                nullableInteger(row, "valuation_score"),
                nullableInteger(row, "balance_sheet_score"),
                nullableInteger(row, "buy_score"),
                row.getString("buy_label"),
                nullableInteger(row, "appeal_score"),
                nullableInteger(row, "crowding_score"),
                row.getString("valuation_basis"),
                row.getBoolean("valuation_eligible"),
                warnings(row.getString("valuation_warnings")),
                row.getString("fundamentals_status"),
                row.getObject("latest_periodic_report_date", java.time.LocalDate.class),
                row.getObject("latest_periodic_filing_date", java.time.LocalDate.class),
                row.getString("latest_periodic_form"),
                nullableInteger(row, "fundamentals_lag_days"),
                warnings(row.getString("score_warnings")),
                nullableInteger(row, "price_bottom_score"),
                nullableInteger(row, "volume_confirmation_score"),
                nullableInteger(row, "failure_risk_score"),
                nullableInteger(row, "confirmed_bottom_score"),
                row.getString("confirmed_bottom_state"),
                row.getObject("confirmed_bottom_signal_date", java.time.LocalDate.class),
                row.getString("reversal_status"),
                nullableInteger(row, "reversal_score"),
                warnings(row.getString("price_signal_reasons")),
                macdTiming(row.getString("macd_timing")),
                row.getString("execution_action"),
                row.getObject("updated_at", java.time.OffsetDateTime.class).toInstant()
        );
    }

    private List<String> warnings(String json) {
        try {
            return json == null ? List.of() : List.of(objectMapper.readValue(json, String[].class));
        } catch (RuntimeException error) {
            throw new CompanyResearchSummaryPersistenceException("Unable to decode valuation warnings", error);
        }
    }

    private CompanyMacdTimingSnapshot macdTiming(String json) {
        try {
            return json == null ? null : objectMapper.readValue(json, CompanyMacdTimingSnapshot.class);
        } catch (RuntimeException error) {
            throw new CompanyResearchSummaryPersistenceException("Unable to decode MACD timing", error);
        }
    }

    private static Double nullableDouble(ResultSet row, String column) throws SQLException {
        return row.getObject(column, Double.class);
    }

    private static Integer nullableInteger(ResultSet row, String column) throws SQLException {
        return row.getObject(column, Integer.class);
    }

    private static String normalize(String ticker) {
        if (ticker == null || ticker.isBlank()) throw new IllegalArgumentException("ticker is required");
        var normalized = ticker.trim().toUpperCase(Locale.ROOT).replace('.', '-');
        if (!SAFE_TICKER.matcher(normalized).matches()) throw new IllegalArgumentException("invalid ticker");
        return normalized;
    }
}
