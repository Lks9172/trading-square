package io.macrosquare.execution.adapter.out.persistence;

import io.macrosquare.execution.application.port.out.InvestmentExecutionPersistenceException;
import io.macrosquare.execution.application.port.out.InvestmentPlanRepository;
import io.macrosquare.execution.application.port.out.TradeLogRepository;
import io.macrosquare.execution.application.port.out.TrancheRepository;
import io.macrosquare.execution.domain.model.InvestmentHorizon;
import io.macrosquare.execution.domain.model.InvestmentPlan;
import io.macrosquare.execution.domain.model.TradeLogEntry;
import io.macrosquare.execution.domain.model.TradeLogKind;
import io.macrosquare.execution.domain.model.TradeLogValue;
import io.macrosquare.execution.domain.model.TrancheEntry;
import io.macrosquare.shared.adapter.out.persistence.PostgresTemporal;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

/** PostgreSQL adapter for the execution aggregate and append-only audit data. */
public final class JdbcInvestmentExecutionAdapter
        implements InvestmentPlanRepository, TradeLogRepository, TrancheRepository {

    /** ASCII "MSQP" in PostgreSQL's two-int advisory-lock namespace. */
    private static final int PLAN_LOCK_NAMESPACE = 1_297_305_936;
    private static final int PLAN_LOCK_AGGREGATE = 1;

    private final NamedParameterJdbcOperations jdbc;
    private final TransactionOperations transactions;
    private final ObjectMapper objectMapper;

    public JdbcInvestmentExecutionAdapter(
            NamedParameterJdbcOperations jdbc,
            TransactionOperations transactions,
            ObjectMapper objectMapper
    ) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    /** Keeps the concrete Spring composition signature stable for rolling builds. */
    public JdbcInvestmentExecutionAdapter(
            NamedParameterJdbcTemplate jdbc,
            TransactionOperations transactions,
            ObjectMapper objectMapper
    ) {
        this((NamedParameterJdbcOperations) jdbc, transactions, objectMapper);
    }

    @Override
    public Optional<InvestmentPlan> load() {
        try {
            return loadPlan(false);
        } catch (RuntimeException error) {
            throw persistenceFailure("Unable to load investment plan", error);
        }
    }

    @Override
    public InvestmentPlan save(InvestmentPlan plan) {
        Objects.requireNonNull(plan, "plan");
        try {
            return Objects.requireNonNull(transactions.execute(status -> {
                lockInvestmentPlanAggregate();
                upsertPlan(plan);
                return plan;
            }), "transaction result");
        } catch (RuntimeException error) {
            throw persistenceFailure("Unable to persist investment plan", error);
        }
    }

    @Override
    public PlanMutation updateAtomically(
            InvestmentPlan initialPlan,
            UnaryOperator<InvestmentPlan> mutation
    ) {
        Objects.requireNonNull(initialPlan, "initialPlan");
        Objects.requireNonNull(mutation, "mutation");
        try {
            return Objects.requireNonNull(transactions.execute(status -> {
                // The transaction advisory lock also protects first-write initialization,
                // where SELECT FOR UPDATE has no row to lock yet. Existing rows receive
                // the conventional row lock as an additional aggregate boundary.
                lockInvestmentPlanAggregate();
                var before = loadPlan(true).orElse(initialPlan);
                var after = applyMutation(mutation, before);
                upsertPlan(after);
                return new PlanMutation(before, after);
            }), "transaction result");
        } catch (PlanMutationFailure error) {
            // Validation and policy failures belong to the application boundary,
            // not persistence. Preserve their type so HTTP error mapping stays intact.
            throw error.failure();
        } catch (RuntimeException error) {
            throw persistenceFailure("Unable to atomically update investment plan", error);
        }
    }

    @Override
    public void append(TradeLogEntry entry) {
        Objects.requireNonNull(entry);
        try {
            jdbc.update("""
                    insert into execution.trade_log (
                        occurred_at, kind, asset, from_value, to_value, notes,
                        against_system_recommendation, context
                    ) values (
                        :occurredAt, :kind, :asset, :fromValue, :toValue, :notes,
                        :againstRecommendation, cast(:context as jsonb)
                    )
                    """, new MapSqlParameterSource()
                    .addValue("occurredAt", PostgresTemporal.timestamp(entry.timestamp()))
                    .addValue("kind", entry.kind().value())
                    .addValue("asset", entry.asset())
                    .addValue("fromValue", entry.from())
                    .addValue("toValue", entry.to())
                    .addValue("notes", entry.notes())
                    .addValue("againstRecommendation", entry.againstSystemRecommendation())
                    .addValue("context", objectMapper.writeValueAsString(plainObject(entry.context()))));
        } catch (RuntimeException error) {
            throw persistenceFailure("Unable to append trade log", error);
        }
    }

    @Override
    public List<TradeLogEntry> recent(int limit) {
        if (limit <= 0) return List.of();
        try {
            var values = new ArrayList<>(jdbc.query("""
                    select occurred_at, kind, asset, from_value, to_value, notes,
                           against_system_recommendation, context
                    from execution.trade_log
                    order by occurred_at desc, id desc
                    limit :limit
                    """, new MapSqlParameterSource("limit", limit), (row, ignored) -> tradeLog(row)));
            Collections.reverse(values);
            return List.copyOf(values);
        } catch (RuntimeException error) {
            throw persistenceFailure("Unable to load trade log", error);
        }
    }

    @Override
    public List<TrancheEntry> append(TrancheEntry entry) {
        Objects.requireNonNull(entry);
        try {
            return transactions.execute(status -> {
                jdbc.update("""
                        insert into execution.tranche_entry (
                            asset, stage, executed_at, price_at_entry, regime_at_entry, weight_pct
                        ) values (
                            :asset, :stage, :executedAt, :price, :regime, :weight
                        )
                        """, trancheParameters(entry));
                return findAll();
            });
        } catch (RuntimeException error) {
            throw persistenceFailure("Unable to append tranche", error);
        }
    }

    @Override
    public List<TrancheEntry> findAll() {
        try {
            return jdbc.query("""
                    select asset, stage, executed_at, price_at_entry, regime_at_entry, weight_pct
                    from execution.tranche_entry
                    order by id
                    """, new MapSqlParameterSource(), (row, ignored) -> tranche(row));
        } catch (RuntimeException error) {
            throw persistenceFailure("Unable to load tranches", error);
        }
    }

    @Override
    public List<TrancheEntry> clearAsset(String asset) {
        if (asset == null || !TrancheEntry.ALLOWED_ASSETS.contains(asset)) {
            throw new IllegalArgumentException("invalid tranche asset");
        }
        try {
            return transactions.execute(status -> {
                jdbc.update("delete from execution.tranche_entry where asset = :asset",
                        new MapSqlParameterSource("asset", asset));
                return findAll();
            });
        } catch (RuntimeException error) {
            throw persistenceFailure("Unable to clear tranches", error);
        }
    }

    private void lockInvestmentPlanAggregate() {
        // Two integer keys keep this aggregate lock in PostgreSQL's dedicated
        // two-key advisory-lock namespace. It is released automatically at tx end.
        jdbc.query(
                "select pg_advisory_xact_lock(:namespace, :aggregate)",
                new MapSqlParameterSource()
                        .addValue("namespace", PLAN_LOCK_NAMESPACE)
                        .addValue("aggregate", PLAN_LOCK_AGGREGATE),
                (row, ignored) -> 0
        );
    }

    private Optional<InvestmentPlan> loadPlan(boolean forUpdate) {
        var sql = "select * from execution.investment_plan where singleton_id = 1"
                + (forUpdate ? " for update" : "");
        return jdbc.query(sql, new MapSqlParameterSource(), (row, ignored) -> plan(row))
                .stream()
                .findFirst();
    }

    private void upsertPlan(InvestmentPlan plan) {
        jdbc.update("""
                insert into execution.investment_plan (
                    singleton_id, version, horizon, target_return_annual_pct,
                    max_drawdown_tolerance_pct, rebalance_interval_days, leverage_max_pct,
                    profit_take_target_pct, stop_loss_pct, monthly_dca_krw,
                    current_holdings, total_capital_krw, total_capital_usd,
                    current_holdings_usd, account_start_date, starting_capital_usd,
                    starting_capital_krw, investment_experience_years, account_type,
                    notes, updated_at
                ) values (
                    1, 1, :horizon, :targetReturn, :maxDrawdown, :rebalanceDays,
                    :leverage, :profitTake, :stopLoss, :monthlyDca,
                    cast(:holdings as jsonb), :totalKrw, :totalUsd,
                    cast(:holdingsUsd as jsonb), :accountStartDate, :startingUsd,
                    :startingKrw, :experienceYears, :accountType, :notes, :updatedAt
                )
                on conflict (singleton_id) do update set
                    version = execution.investment_plan.version + 1,
                    horizon = excluded.horizon,
                    target_return_annual_pct = excluded.target_return_annual_pct,
                    max_drawdown_tolerance_pct = excluded.max_drawdown_tolerance_pct,
                    rebalance_interval_days = excluded.rebalance_interval_days,
                    leverage_max_pct = excluded.leverage_max_pct,
                    profit_take_target_pct = excluded.profit_take_target_pct,
                    stop_loss_pct = excluded.stop_loss_pct,
                    monthly_dca_krw = excluded.monthly_dca_krw,
                    current_holdings = excluded.current_holdings,
                    total_capital_krw = excluded.total_capital_krw,
                    total_capital_usd = excluded.total_capital_usd,
                    current_holdings_usd = excluded.current_holdings_usd,
                    account_start_date = excluded.account_start_date,
                    starting_capital_usd = excluded.starting_capital_usd,
                    starting_capital_krw = excluded.starting_capital_krw,
                    investment_experience_years = excluded.investment_experience_years,
                    account_type = excluded.account_type,
                    notes = excluded.notes,
                    updated_at = excluded.updated_at
                """, planParameters(plan));
    }

    private static InvestmentPlan applyMutation(
            UnaryOperator<InvestmentPlan> mutation,
            InvestmentPlan current
    ) {
        try {
            return Objects.requireNonNull(mutation.apply(current), "mutation result");
        } catch (RuntimeException failure) {
            throw new PlanMutationFailure(failure);
        }
    }

    private MapSqlParameterSource planParameters(InvestmentPlan plan) {
        return new MapSqlParameterSource()
                .addValue("horizon", plan.horizon().value())
                .addValue("targetReturn", plan.targetReturnAnnualPct())
                .addValue("maxDrawdown", plan.maxDrawdownTolerancePct())
                .addValue("rebalanceDays", plan.rebalanceIntervalDays())
                .addValue("leverage", plan.leverageMaxPct())
                .addValue("profitTake", plan.profitTakeTargetPct())
                .addValue("stopLoss", plan.stopLossPct())
                .addValue("monthlyDca", plan.monthlyDcaKrw())
                .addValue("holdings", jsonOrNull(plan.currentHoldings()))
                .addValue("totalKrw", plan.totalCapitalKrw())
                .addValue("totalUsd", plan.totalCapitalUsd())
                .addValue("holdingsUsd", jsonOrNull(plan.currentHoldingsUsd()))
                .addValue("accountStartDate", plan.accountStartDate())
                .addValue("startingUsd", plan.startingCapitalUsd())
                .addValue("startingKrw", plan.startingCapitalKrw())
                .addValue("experienceYears", plan.investmentExperienceYears())
                .addValue("accountType", plan.accountType())
                .addValue("notes", plan.notes())
                .addValue("updatedAt", PostgresTemporal.timestamp(plan.updatedAt()));
    }

    private InvestmentPlan plan(ResultSet row) throws SQLException {
        return new InvestmentPlan(
                InvestmentHorizon.from(row.getString("horizon")),
                row.getDouble("target_return_annual_pct"),
                row.getDouble("max_drawdown_tolerance_pct"),
                row.getInt("rebalance_interval_days"),
                row.getDouble("leverage_max_pct"),
                row.getDouble("profit_take_target_pct"),
                row.getDouble("stop_loss_pct"),
                row.getLong("monthly_dca_krw"),
                holdings(row.getString("current_holdings")),
                nullableLong(row, "total_capital_krw"),
                nullableDouble(row, "total_capital_usd"),
                holdings(row.getString("current_holdings_usd")),
                row.getObject("account_start_date", java.time.LocalDate.class),
                nullableDouble(row, "starting_capital_usd"),
                nullableLong(row, "starting_capital_krw"),
                nullableDouble(row, "investment_experience_years"),
                row.getString("account_type"),
                row.getString("notes"),
                row.getObject("updated_at", java.time.OffsetDateTime.class).toInstant()
        );
    }

    private TradeLogEntry tradeLog(ResultSet row) throws SQLException {
        var context = new LinkedHashMap<String, TradeLogValue>();
        var root = objectMapper.readTree(row.getString("context"));
        if (root != null && root.isObject()) {
            root.properties().forEach(value -> context.put(value.getKey(), structuredValue(value.getValue(), 0)));
        }
        return new TradeLogEntry(
                row.getObject("occurred_at", java.time.OffsetDateTime.class).toInstant(),
                TradeLogKind.from(row.getString("kind")),
                row.getString("asset"),
                row.getString("from_value"),
                row.getString("to_value"),
                row.getString("notes"),
                row.getObject("against_system_recommendation", Boolean.class),
                context
        );
    }

    private static TrancheEntry tranche(ResultSet row) throws SQLException {
        return new TrancheEntry(
                row.getString("asset"),
                row.getInt("stage"),
                row.getObject("executed_at", java.time.OffsetDateTime.class).toInstant(),
                nullableDouble(row, "price_at_entry"),
                row.getString("regime_at_entry"),
                nullableDouble(row, "weight_pct")
        );
    }

    private static MapSqlParameterSource trancheParameters(TrancheEntry entry) {
        return new MapSqlParameterSource()
                .addValue("asset", entry.asset())
                .addValue("stage", entry.stage())
                .addValue("executedAt", PostgresTemporal.timestamp(entry.executedAt()))
                .addValue("price", entry.priceAtEntry())
                .addValue("regime", entry.regimeAtEntry())
                .addValue("weight", entry.weightPct());
    }

    private String jsonOrNull(Object value) {
        return value == null ? null : objectMapper.writeValueAsString(value);
    }

    private Map<String, Double> holdings(String json) {
        if (json == null) return null;
        var root = objectMapper.readTree(json);
        if (root == null || !root.isObject()) throw new IllegalArgumentException("holdings must be a JSON object");
        var result = new LinkedHashMap<String, Double>();
        root.properties().forEach(entry -> {
            if (!entry.getValue().isNumber()) throw new IllegalArgumentException("holding value must be numeric");
            result.put(entry.getKey(), entry.getValue().asDouble());
        });
        return result;
    }

    private static Map<String, Object> plainObject(Map<String, TradeLogValue> values) {
        var result = new LinkedHashMap<String, Object>();
        values.forEach((key, value) -> result.put(key, plain(value)));
        return result;
    }

    private static Object plain(TradeLogValue value) {
        return switch (value) {
            case TradeLogValue.TextValue text -> text.value();
            case TradeLogValue.NumberValue number -> number.value();
            case TradeLogValue.BooleanValue bool -> bool.value();
            case TradeLogValue.ObjectValue object -> plainObject(object.fields());
            case TradeLogValue.ArrayValue array -> array.values().stream()
                    .map(JdbcInvestmentExecutionAdapter::plain).toList();
            case TradeLogValue.NullValue ignored -> null;
        };
    }

    private static TradeLogValue structuredValue(JsonNode node, int depth) {
        if (depth > 8) throw new IllegalArgumentException("trade log context is too deep");
        if (node == null || node.isNull()) return TradeLogValue.NullValue.INSTANCE;
        if (node.isString()) return new TradeLogValue.TextValue(node.stringValue());
        if (node.isBoolean()) return new TradeLogValue.BooleanValue(node.booleanValue());
        if (node.isIntegralNumber()) return new TradeLogValue.NumberValue(node.longValue());
        if (node.isNumber()) return new TradeLogValue.NumberValue(node.decimalValue());
        if (node.isArray()) {
            var values = new ArrayList<TradeLogValue>();
            node.forEach(value -> values.add(structuredValue(value, depth + 1)));
            return new TradeLogValue.ArrayValue(values);
        }
        if (node.isObject()) {
            var values = new LinkedHashMap<String, TradeLogValue>();
            node.properties().forEach(value ->
                    values.put(value.getKey(), structuredValue(value.getValue(), depth + 1)));
            return new TradeLogValue.ObjectValue(values);
        }
        throw new IllegalArgumentException("unsupported trade log context value");
    }

    private static Double nullableDouble(ResultSet row, String column) throws SQLException {
        var value = row.getDouble(column);
        return row.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet row, String column) throws SQLException {
        var value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private static InvestmentExecutionPersistenceException persistenceFailure(String message, Throwable cause) {
        return new InvestmentExecutionPersistenceException(message, cause);
    }

    private static final class PlanMutationFailure extends RuntimeException {
        private final RuntimeException failure;

        private PlanMutationFailure(RuntimeException failure) {
            super(null, failure, false, false);
            this.failure = failure;
        }

        private RuntimeException failure() {
            return failure;
        }
    }
}
