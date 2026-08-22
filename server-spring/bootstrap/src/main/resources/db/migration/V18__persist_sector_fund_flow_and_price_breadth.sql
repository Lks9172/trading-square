create table research.sector_fund_flow_snapshot (
    sector_key varchar(32) not null,
    fund_ticker varchar(16) not null,
    observed_on date not null,
    nav numeric(24, 8) not null,
    shares_outstanding numeric(28, 4) not null,
    total_net_assets numeric(28, 2) not null,
    flow_1d_usd numeric(28, 2) not null,
    flow_5d_usd numeric(28, 2) not null,
    flow_20d_usd numeric(28, 2) not null,
    flow_5d_pct double precision not null,
    flow_20d_pct double precision not null,
    flow_score smallint not null,
    provider varchar(64) not null,
    collected_at timestamptz not null,
    primary key (sector_key, observed_on),
    constraint sector_fund_flow_key_ck check (sector_key ~ '^SECTOR_XL[A-Z]{1,2}$'),
    constraint sector_fund_flow_ticker_ck check (fund_ticker ~ '^XL[A-Z]{1,2}$'),
    constraint sector_fund_flow_positive_ck check (
        nav > 0 and shares_outstanding > 0 and total_net_assets > 0
    ),
    constraint sector_fund_flow_numeric_finite_ck check (
        nav not in ('NaN'::numeric, 'Infinity'::numeric, '-Infinity'::numeric)
        and shares_outstanding not in ('NaN'::numeric, 'Infinity'::numeric, '-Infinity'::numeric)
        and total_net_assets not in ('NaN'::numeric, 'Infinity'::numeric, '-Infinity'::numeric)
        and flow_1d_usd not in ('NaN'::numeric, 'Infinity'::numeric, '-Infinity'::numeric)
        and flow_5d_usd not in ('NaN'::numeric, 'Infinity'::numeric, '-Infinity'::numeric)
        and flow_20d_usd not in ('NaN'::numeric, 'Infinity'::numeric, '-Infinity'::numeric)
    ),
    constraint sector_fund_flow_finite_ck check (
        flow_5d_pct not in ('NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision)
        and flow_20d_pct not in ('NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision)
    ),
    constraint sector_fund_flow_score_ck check (flow_score between 0 and 100)
);

create index sector_fund_flow_latest_idx
    on research.sector_fund_flow_snapshot (sector_key, observed_on desc)
    include (flow_score, flow_5d_pct, flow_20d_pct, collected_at);

create table research.sector_price_breadth_snapshot (
    sector_key varchar(32) not null,
    observed_on date not null,
    oldest_component_on date not null,
    latest_component_on date not null,
    constituent_count integer not null,
    covered_count integer not null,
    above_ma20_count integer not null,
    above_ma50_count integer not null,
    above_ma200_count integer not null,
    breadth_score smallint not null,
    provider varchar(64) not null,
    collected_at timestamptz not null,
    primary key (sector_key, observed_on),
    constraint sector_price_breadth_key_ck check (sector_key ~ '^SECTOR_XL[A-Z]{1,2}$'),
    constraint sector_price_breadth_dates_ck check (
        oldest_component_on <= latest_component_on and latest_component_on <= observed_on
    ),
    constraint sector_price_breadth_coverage_ck check (
        constituent_count >= 1
        and covered_count between 1 and constituent_count
        and above_ma20_count between 0 and covered_count
        and above_ma50_count between 0 and covered_count
        and above_ma200_count between 0 and covered_count
    ),
    constraint sector_price_breadth_score_ck check (breadth_score between 0 and 100)
);

create index sector_price_breadth_latest_idx
    on research.sector_price_breadth_snapshot (sector_key, observed_on desc)
    include (breadth_score, covered_count, constituent_count, collected_at);

comment on table research.sector_fund_flow_snapshot is
    'State Street official sector-ETF NAV/shares history transformed into creation-redemption activity.';
comment on table research.sector_price_breadth_snapshot is
    'Equal-count 20/50/200-day moving-average participation for tracked sector constituents.';
