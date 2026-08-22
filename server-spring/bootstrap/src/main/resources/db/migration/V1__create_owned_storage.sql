create schema if not exists market;
create schema if not exists company;
create schema if not exists execution;
create schema if not exists notification;
create schema if not exists storage;

create table market.observation (
    source varchar(32) not null,
    series_key varchar(128) not null,
    provider_code varchar(128) not null,
    observed_on date not null,
    value double precision not null,
    collected_at timestamptz not null default clock_timestamp(),
    constraint market_observation_pk primary key (source, series_key, observed_on),
    constraint market_observation_source_ck check (source in (
        'FRED', 'YAHOO', 'FEAR_GREED', 'SENTIMENT', 'STABLECOIN'
    )),
    constraint market_observation_identity_ck check (
        btrim(series_key) <> '' and btrim(provider_code) <> ''
    ),
    constraint market_observation_value_ck check (
        value not in ('NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision)
    )
);

create index market_observation_latest_idx
    on market.observation (source, series_key, observed_on desc)
    include (provider_code, value);

create table company.analyst_series_state (
    ticker varchar(20) primary key,
    updated_at timestamptz not null,
    constraint analyst_series_ticker_ck check (ticker ~ '^[A-Z0-9][A-Z0-9.-]{0,19}$')
);

create table company.analyst_snapshot (
    ticker varchar(20) not null references company.analyst_series_state(ticker) on delete cascade,
    observed_on date not null,
    analyst_score double precision,
    upside_pct double precision,
    collected_at timestamptz not null default clock_timestamp(),
    constraint analyst_snapshot_pk primary key (ticker, observed_on),
    constraint analyst_score_ck check (
        analyst_score is null or (
            analyst_score between -2 and 2
            and analyst_score not in ('NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision)
        )
    ),
    constraint analyst_upside_ck check (
        upside_pct is null or upside_pct not in (
            'NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision
        )
    )
);

create index analyst_snapshot_ticker_date_idx
    on company.analyst_snapshot (ticker, observed_on desc)
    include (analyst_score, upside_pct);

create table execution.investment_plan (
    singleton_id smallint primary key default 1,
    version bigint not null default 1,
    horizon varchar(16) not null,
    target_return_annual_pct double precision not null,
    max_drawdown_tolerance_pct double precision not null,
    rebalance_interval_days integer not null,
    leverage_max_pct double precision not null,
    profit_take_target_pct double precision not null,
    stop_loss_pct double precision not null,
    monthly_dca_krw bigint not null,
    current_holdings jsonb,
    total_capital_krw bigint,
    total_capital_usd double precision,
    current_holdings_usd jsonb,
    account_start_date date,
    starting_capital_usd double precision,
    starting_capital_krw bigint,
    investment_experience_years double precision,
    account_type varchar(32),
    notes varchar(4000),
    updated_at timestamptz not null,
    constraint investment_plan_singleton_ck check (singleton_id = 1),
    constraint investment_plan_horizon_ck check (horizon in ('short', 'medium', 'long')),
    constraint investment_plan_numeric_ck check (
        target_return_annual_pct between -100 and 1000
        and max_drawdown_tolerance_pct between 0 and 100
        and rebalance_interval_days between 1 and 3650
        and leverage_max_pct between 0 and 100
        and profit_take_target_pct between 0 and 1000
        and stop_loss_pct between 0 and 100
        and monthly_dca_krw >= 0
        and target_return_annual_pct not in ('NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision)
        and max_drawdown_tolerance_pct not in ('NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision)
        and leverage_max_pct not in ('NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision)
        and profit_take_target_pct not in ('NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision)
        and stop_loss_pct not in ('NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision)
        and (total_capital_krw is null or total_capital_krw >= 0)
        and (starting_capital_krw is null or starting_capital_krw >= 0)
        and (total_capital_usd is null or (
            total_capital_usd >= 0
            and total_capital_usd not in ('NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision)
        ))
        and (starting_capital_usd is null or (
            starting_capital_usd >= 0
            and starting_capital_usd not in ('NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision)
        ))
        and (investment_experience_years is null or (
            investment_experience_years >= 0
            and investment_experience_years not in ('NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision)
        ))
    ),
    constraint investment_plan_account_type_ck check (
        account_type is null or account_type in ('general', 'isa', 'pension', 'foreign')
    ),
    constraint investment_plan_holdings_ck check (
        (current_holdings is null or jsonb_typeof(current_holdings) = 'object')
        and (current_holdings_usd is null or jsonb_typeof(current_holdings_usd) = 'object')
    )
);

create table execution.tranche_entry (
    id bigint generated always as identity primary key,
    asset varchar(16) not null,
    stage smallint not null,
    executed_at timestamptz not null,
    price_at_entry double precision,
    regime_at_entry varchar(64),
    weight_pct double precision,
    constraint tranche_asset_ck check (asset in (
        'NASDAQ', 'KOSPI', 'GOLD', 'SILVER', 'COPPER', 'LEVERAGE', 'EMERGING'
    )),
    constraint tranche_stage_ck check (stage between 1 and 5),
    constraint tranche_price_ck check (price_at_entry is null or (
        price_at_entry > 0
        and price_at_entry not in ('NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision)
    )),
    constraint tranche_weight_ck check (weight_pct is null or (
        weight_pct between 0 and 100
        and weight_pct not in ('NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision)
    ))
);

create index tranche_asset_time_idx
    on execution.tranche_entry (asset, executed_at desc, id desc);

create table execution.trade_log (
    id bigint generated always as identity primary key,
    occurred_at timestamptz not null,
    kind varchar(32) not null,
    asset varchar(64),
    from_value varchar(256),
    to_value varchar(256),
    notes varchar(4000),
    against_system_recommendation boolean,
    context jsonb not null default '{}'::jsonb,
    constraint trade_log_kind_ck check (kind in (
        'signal_change', 'allocation_change', 'user_action', 'observation'
    )),
    constraint trade_log_context_ck check (jsonb_typeof(context) = 'object')
);

create index trade_log_time_idx on execution.trade_log (occurred_at desc, id desc);
create index trade_log_asset_time_idx
    on execution.trade_log (asset, occurred_at desc, id desc)
    where asset is not null;

create table notification.delivery_state (
    channel varchar(32) primary key,
    market_fingerprint varchar(512) not null default '',
    updated_at timestamptz not null,
    version bigint not null default 1
);

create table notification.candidate_key (
    channel varchar(32) not null references notification.delivery_state(channel) on delete cascade,
    candidate_key varchar(128) not null,
    constraint notification_candidate_key_pk primary key (channel, candidate_key)
);

create table notification.candidate_snapshot (
    channel varchar(32) not null references notification.delivery_state(channel) on delete cascade,
    candidate_key varchar(128) not null,
    kind varchar(16) not null,
    symbol varchar(32) not null,
    name varchar(256) not null,
    classification varchar(256) not null default '',
    bottom_state varchar(16) not null,
    bottom_score integer,
    total_score integer not null,
    buy_score integer not null,
    action varchar(32) not null default '',
    signal_date date,
    reversal_status varchar(32) not null default 'OFF',
    reversal_score integer,
    reasons jsonb not null default '[]'::jsonb,
    constraint notification_candidate_snapshot_pk primary key (channel, candidate_key),
    constraint notification_candidate_kind_ck check (kind in ('COMPANY', 'CRYPTO')),
    constraint notification_candidate_bottom_ck check (bottom_state in ('UNMET', 'CANDIDATE', 'CONVICTION')),
    constraint notification_candidate_scores_ck check (
        total_score between 0 and 100
        and buy_score between 0 and 100
        and (bottom_score is null or bottom_score between 0 and 100)
        and (reversal_score is null or reversal_score between 0 and 100)
    ),
    constraint notification_candidate_reasons_ck check (jsonb_typeof(reasons) = 'array')
);

create table storage.object_artifact (
    id uuid primary key,
    bucket varchar(63) not null,
    object_key varchar(1024) not null,
    version_id varchar(256) not null default '',
    etag varchar(128) not null,
    checksum_sha256 char(64) not null,
    content_type varchar(255) not null,
    size_bytes bigint not null,
    metadata jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default clock_timestamp(),
    constraint object_artifact_identity_uq unique (bucket, object_key, version_id),
    constraint object_artifact_size_ck check (size_bytes >= 0),
    constraint object_artifact_sha_ck check (checksum_sha256 ~ '^[0-9a-f]{64}$'),
    constraint object_artifact_metadata_ck check (jsonb_typeof(metadata) = 'object')
);

create index object_artifact_key_time_idx
    on storage.object_artifact (bucket, object_key, created_at desc);

create table storage.object_pointer (
    bucket varchar(63) not null,
    object_key varchar(1024) not null,
    artifact_id uuid not null references storage.object_artifact(id),
    generation bigint not null default 1,
    activated_at timestamptz not null default clock_timestamp(),
    constraint object_pointer_pk primary key (bucket, object_key),
    constraint object_pointer_generation_ck check (generation > 0)
);

create index object_pointer_artifact_idx on storage.object_pointer (artifact_id);

comment on schema storage is
    'Metadata and active pointers only. Binary/JSON file bodies are stored in the versioned MinIO bucket.';
