create table market.collection_status (
    source varchar(32) primary key,
    status varchar(16) not null,
    attempted_at timestamptz not null,
    completed_at timestamptz not null,
    collected_count integer not null,
    persisted_count integer not null,
    failure_keys varchar(2000) not null default '',
    failure_type varchar(128) not null default '',
    constraint market_collection_status_source_ck check (source in (
        'FRED', 'YAHOO', 'FEAR_GREED', 'SENTIMENT', 'STABLECOIN', 'KRX'
    )),
    constraint market_collection_status_state_ck check (status in ('SUCCESS', 'DEGRADED', 'FAILED')),
    constraint market_collection_status_time_ck check (completed_at >= attempted_at),
    constraint market_collection_status_counts_ck check (
        collected_count >= 0 and persisted_count >= 0 and persisted_count <= collected_count
    )
);

comment on table market.collection_status is
    'Last collector attempt metadata. This operational evidence is never used as an investment score.';
