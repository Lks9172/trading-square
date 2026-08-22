create table research.narrative_source_observation (
    theme_id varchar(64) not null,
    source_key varchar(64) not null,
    source_label varchar(160) not null,
    observation_date date not null,
    revision integer not null,
    observed_at timestamptz not null,
    quality varchar(32) not null,
    status varchar(16) not null,
    value numeric(30, 8),
    score numeric(5, 2) not null,
    detail text not null,
    source_url varchar(2048) not null,
    content_hash char(64) not null,
    raw_object_key varchar(1024),
    primary key (theme_id, source_key, observation_date, revision),
    constraint narrative_source_revision_positive check (revision > 0),
    constraint narrative_source_score_range check (score between 0 and 10),
    constraint narrative_source_quality_valid check (quality in (
        'OFFICIAL_PRIMARY', 'VERIFIED_API', 'PUBLIC_API', 'PUBLIC_FEED',
        'HTML_PROXY', 'LEGACY_UNKNOWN'
    )),
    constraint narrative_source_status_valid check (status in ('AVAILABLE', 'MISSING', 'FAILED')),
    constraint narrative_source_available_value check (status <> 'AVAILABLE' or value is not null),
    constraint narrative_source_hash_format check (content_hash ~ '^[0-9a-f]{64}$')
);

create index narrative_source_latest_idx
    on research.narrative_source_observation (
        theme_id, source_key, observation_date desc, revision desc, observed_at desc
    );

create index narrative_source_health_idx
    on research.narrative_source_observation (status, observed_at desc);

comment on table research.narrative_source_observation is
    'Immutable daily narrative source observations. Same-day changes append a revision; raw payloads live in MinIO.';
