create table company.research_summary (
    ticker varchar(20) primary key,
    fundamentals_as_of date,
    market_cap double precision,
    revenue_growth_yoy double precision,
    operating_margin double precision,
    ev_to_sales double precision,
    total_score integer,
    growth_score integer,
    quality_score integer,
    valuation_score integer,
    balance_sheet_score integer,
    buy_score integer,
    buy_label varchar(32),
    appeal_score integer,
    crowding_score integer,
    valuation_basis varchar(32) not null,
    valuation_eligible boolean not null,
    valuation_warnings jsonb not null default '[]'::jsonb,
    price_bottom_score integer,
    volume_confirmation_score integer,
    failure_risk_score integer,
    confirmed_bottom_score integer,
    confirmed_bottom_state varchar(16),
    updated_at timestamptz not null,
    constraint company_research_summary_ticker_ck check (
        ticker ~ '^[A-Z0-9][A-Z0-9.-]{0,19}$'
    ),
    constraint company_research_summary_market_cap_ck check (
        market_cap is null or (
            market_cap > 0 and market_cap not in (
                'NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision
            )
        )
    ),
    constraint company_research_summary_metric_ck check (
        (revenue_growth_yoy is null or revenue_growth_yoy between -100 and 200)
        and (operating_margin is null or operating_margin between -100 and 100)
        and (ev_to_sales is null or ev_to_sales between 0 and 100)
    ),
    constraint company_research_summary_scores_ck check (
        (total_score is null or total_score between 0 and 100)
        and (growth_score is null or growth_score between 0 and 100)
        and (quality_score is null or quality_score between 0 and 100)
        and (valuation_score is null or valuation_score between 0 and 100)
        and (balance_sheet_score is null or balance_sheet_score between 0 and 100)
        and (buy_score is null or buy_score between 0 and 100)
        and (appeal_score is null or appeal_score between 0 and 100)
        and (crowding_score is null or crowding_score between 0 and 100)
        and (price_bottom_score is null or price_bottom_score between 0 and 100)
        and (volume_confirmation_score is null or volume_confirmation_score between 0 and 100)
        and (failure_risk_score is null or failure_risk_score between 0 and 100)
        and (confirmed_bottom_score is null or confirmed_bottom_score between 0 and 100)
    ),
    constraint company_research_summary_basis_ck check (
        valuation_basis in ('INDEPENDENT_MARKET_CAP', 'SEC_SHARES', 'UNAVAILABLE')
    ),
    constraint company_research_summary_bottom_state_ck check (
        confirmed_bottom_state is null or confirmed_bottom_state in ('UNMET', 'CANDIDATE', 'CONVICTION')
    ),
    constraint company_research_summary_warnings_ck check (
        jsonb_typeof(valuation_warnings) = 'array'
    )
);

create index company_research_summary_rank_idx
    on company.research_summary (buy_score desc nulls last, total_score desc nulls last, ticker);

create index company_research_summary_updated_idx
    on company.research_summary (updated_at desc);

comment on table company.research_summary is
    'Current Spring-recomputed company list metrics; captured migration JSON is membership metadata only.';
