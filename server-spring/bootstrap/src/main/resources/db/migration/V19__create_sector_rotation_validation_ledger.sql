create table research.sector_rotation_run (
    run_id uuid primary key,
    calculated_at timestamptz not null,
    as_of_date date not null,
    methodology_version varchar(96) not null,
    regime varchar(32) not null,
    regime_confidence smallint not null,
    current_momentum_coverage smallint not null,
    total_return_coverage smallint not null,
    universe_size smallint not null,
    oldest_macro_observed_on date,
    latest_macro_observed_on date,
    created_at timestamptz not null default clock_timestamp(),
    constraint sector_rotation_run_session_uk unique (methodology_version, as_of_date),
    constraint sector_rotation_run_method_ck check (btrim(methodology_version) <> ''),
    constraint sector_rotation_run_regime_ck check (regime in (
        'MID_GROWTH', 'EARLY_CYCLICAL', 'LATE_INFLATION', 'DEFENSIVE', 'RE_ACCELERATION'
    )),
    constraint sector_rotation_run_score_ck check (regime_confidence between 0 and 100),
    constraint sector_rotation_run_coverage_ck check (
        universe_size = 11
        and current_momentum_coverage between 0 and universe_size
        and total_return_coverage between 0 and universe_size
    ),
    constraint sector_rotation_run_dates_ck check (
        (oldest_macro_observed_on is null and latest_macro_observed_on is null)
        or (oldest_macro_observed_on <= latest_macro_observed_on and latest_macro_observed_on <= as_of_date)
    )
);

create table research.sector_rotation_item_snapshot (
    run_id uuid not null references research.sector_rotation_run(run_id) on delete cascade,
    sector_key varchar(32) not null,
    rank smallint not null,
    rotation_score smallint not null,
    macro_fit_score smallint not null,
    relative_strength_score smallint not null,
    fundamental_score smallint not null,
    valuation_score smallint,
    earnings_revision_score smallint,
    fund_flow_score smallint,
    price_breadth_score smallint,
    crowding_relief_score smallint not null,
    state varchar(24) not null,
    rotation_label varchar(32) not null,
    expected_leadership_window varchar(32) not null,
    oldest_momentum_observed_on date,
    latest_momentum_observed_on date,
    revision_observed_on date,
    revision_coverage_pct smallint,
    fund_flow_observed_on date,
    price_breadth_observed_on date,
    price_breadth_coverage_pct smallint,
    constraint sector_rotation_item_pk primary key (run_id, sector_key),
    constraint sector_rotation_item_rank_uk unique (run_id, rank),
    constraint sector_rotation_item_key_ck check (sector_key ~ '^SECTOR_XL[A-Z]{1,2}$'),
    constraint sector_rotation_item_rank_ck check (rank between 1 and 11),
    constraint sector_rotation_item_score_ck check (
        rotation_score between 0 and 100 and macro_fit_score between 0 and 100
        and relative_strength_score between 0 and 100 and fundamental_score between 0 and 100
        and (valuation_score is null or valuation_score between 0 and 100)
        and (earnings_revision_score is null or earnings_revision_score between 0 and 100)
        and (fund_flow_score is null or fund_flow_score between 0 and 100)
        and (price_breadth_score is null or price_breadth_score between 0 and 100)
        and crowding_relief_score between 0 and 100
    ),
    constraint sector_rotation_item_state_ck check (state in ('LEADING', 'IMPROVING', 'WEAKENING', 'LAGGING')),
    constraint sector_rotation_item_label_ck check (rotation_label in (
        'LEADER', 'ROTATION_IN', 'LATE_LEADER', 'DEFENSIVE_HOLD', 'ROTATION_OUT'
    )),
    constraint sector_rotation_item_window_ck check (expected_leadership_window in (
        'NOW', 'ONE_TO_THREE_MONTHS', 'THREE_TO_SIX_MONTHS', 'SIX_MONTHS_PLUS', 'UNCLEAR'
    )),
    constraint sector_rotation_item_momentum_dates_ck check (
        (oldest_momentum_observed_on is null and latest_momentum_observed_on is null)
        or oldest_momentum_observed_on <= latest_momentum_observed_on
    ),
    constraint sector_rotation_item_revision_ck check (
        (revision_observed_on is null and revision_coverage_pct is null)
        or (revision_observed_on is not null and revision_coverage_pct between 0 and 100)
    ),
    constraint sector_rotation_item_flow_ck check (
        (fund_flow_score is null and fund_flow_observed_on is null)
        or (fund_flow_score is not null and fund_flow_observed_on is not null)
    ),
    constraint sector_rotation_item_breadth_ck check (
        (price_breadth_score is null and price_breadth_observed_on is null and price_breadth_coverage_pct is null)
        or (price_breadth_score is not null and price_breadth_observed_on is not null
            and price_breadth_coverage_pct between 0 and 100)
    )
);

create table research.sector_rotation_outcome (
    run_id uuid not null,
    sector_key varchar(32) not null,
    trading_sessions smallint not null,
    start_on date not null,
    end_on date not null,
    sector_return_pct double precision not null,
    benchmark_return_pct double precision not null,
    universe_equal_weight_return_pct double precision not null,
    benchmark_excess_return_pct double precision not null,
    universe_excess_return_pct double precision not null,
    evaluated_at timestamptz not null default clock_timestamp(),
    constraint sector_rotation_outcome_pk primary key (run_id, sector_key, trading_sessions),
    constraint sector_rotation_outcome_item_fk foreign key (run_id, sector_key)
        references research.sector_rotation_item_snapshot(run_id, sector_key) on delete cascade,
    constraint sector_rotation_outcome_horizon_ck check (trading_sessions in (21, 63, 126)),
    constraint sector_rotation_outcome_dates_ck check (start_on < end_on),
    constraint sector_rotation_outcome_finite_ck check (
        sector_return_pct not in ('NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision)
        and benchmark_return_pct not in ('NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision)
        and universe_equal_weight_return_pct not in ('NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision)
        and benchmark_excess_return_pct not in ('NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision)
        and universe_excess_return_pct not in ('NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision)
    )
);

create index sector_rotation_run_asof_idx
    on research.sector_rotation_run (as_of_date desc, methodology_version);
create index sector_rotation_outcome_horizon_idx
    on research.sector_rotation_outcome (trading_sessions, end_on desc);

create function research.reject_sector_rotation_ledger_update()
returns trigger
language plpgsql
as $$
begin
    raise exception 'sector rotation validation ledger is append-only';
end;
$$;

create trigger sector_rotation_run_immutable_trg
before update on research.sector_rotation_run
for each row execute function research.reject_sector_rotation_ledger_update();

create trigger sector_rotation_item_immutable_trg
before update on research.sector_rotation_item_snapshot
for each row execute function research.reject_sector_rotation_ledger_update();

create trigger sector_rotation_outcome_immutable_trg
before update on research.sector_rotation_outcome
for each row execute function research.reject_sector_rotation_ledger_update();

comment on table research.sector_rotation_run is
    'Append-only, one-per-completed-common-session live sector composite output; no synthetic historical backfill.';
comment on table research.sector_rotation_item_snapshot is
    'Point-in-time component scores and evidence dates used by each live standard-sector ranking.';
comment on table research.sector_rotation_outcome is
    '21/63/126 common-trading-session total-return outcomes materialized only after the window exists.';
