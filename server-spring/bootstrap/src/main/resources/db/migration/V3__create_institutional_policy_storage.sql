create schema if not exists institutional;
create schema if not exists policy;

create table institutional.manager (
    cik varchar(10) primary key,
    manager_id varchar(64) not null unique,
    name varchar(256) not null,
    updated_at timestamptz not null default clock_timestamp(),
    constraint institutional_manager_cik_ck check (cik ~ '^[0-9]{10}$'),
    constraint institutional_manager_id_ck check (manager_id ~ '^[a-z0-9][a-z0-9_-]{0,63}$'),
    constraint institutional_manager_name_ck check (btrim(name) <> '')
);

create table institutional.filing (
    accession_number varchar(32) primary key,
    manager_cik varchar(10) not null references institutional.manager(cik),
    filed_on date not null,
    report_period date not null,
    source_url varchar(2048) not null,
    raw_object_key varchar(1024),
    collected_at timestamptz not null default clock_timestamp(),
    constraint institutional_filing_accession_ck check (
        accession_number ~ '^[0-9]{10}-[0-9]{2}-[0-9]{6}$'
    ),
    constraint institutional_filing_dates_ck check (report_period <= filed_on),
    constraint institutional_filing_source_ck check (source_url like 'https://www.sec.gov/%'),
    constraint institutional_filing_object_ck check (
        raw_object_key is null or raw_object_key = '' or raw_object_key like 'sec-filings/13f/%'
    )
);

create index institutional_filing_manager_period_idx
    on institutional.filing (manager_cik, report_period desc, filed_on desc);

create table institutional.holding (
    id bigint generated always as identity primary key,
    accession_number varchar(32) not null
        references institutional.filing(accession_number) on delete cascade,
    cusip varchar(32) not null,
    issuer varchar(512) not null,
    title_class varchar(256) not null,
    put_call varchar(8) not null default '',
    value_usd double precision not null,
    shares double precision not null,
    constraint institutional_holding_identity_ck check (btrim(cusip) <> '' and btrim(issuer) <> ''),
    constraint institutional_holding_put_call_ck check (put_call in ('', 'PUT', 'CALL')),
    constraint institutional_holding_value_ck check (
        value_usd >= 0
        and shares >= 0
        and value_usd not in ('NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision)
        and shares not in ('NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision)
    )
);

create index institutional_holding_filing_value_idx
    on institutional.holding (accession_number, value_usd desc);
create index institutional_holding_cusip_idx
    on institutional.holding (cusip, accession_number);

create table policy.document_analysis (
    document_id varchar(128) primary key,
    source varchar(128) not null,
    title varchar(1024) not null,
    document_type varchar(32) not null,
    published_at timestamptz not null,
    source_url varchar(2048) not null,
    tone varchar(16) not null,
    tone_score integer not null,
    confidence integer not null,
    dovish_weight integer not null,
    hawkish_weight integer not null,
    summary varchar(2000) not null,
    analyzed_at timestamptz not null default clock_timestamp(),
    constraint policy_document_identity_ck check (
        btrim(document_id) <> '' and btrim(source) <> '' and btrim(title) <> ''
    ),
    constraint policy_document_type_ck check (document_type in (
        'FOMC_STATEMENT', 'FOMC_MINUTES', 'ECONOMIC_PROJECTIONS', 'DISCOUNT_RATE_MINUTES', 'OTHER'
    )),
    constraint policy_document_source_ck check (source_url like 'https://www.federalreserve.gov/%'),
    constraint policy_document_tone_ck check (tone in ('DOVISH', 'NEUTRAL', 'HAWKISH', 'MIXED')),
    constraint policy_document_score_ck check (
        tone_score between -100 and 100
        and confidence between 0 and 100
        and dovish_weight >= 0
        and hawkish_weight >= 0
    ),
    constraint policy_document_summary_ck check (btrim(summary) <> '')
);

create index policy_document_published_idx
    on policy.document_analysis (published_at desc, document_id);

create table policy.document_evidence (
    document_id varchar(128) not null
        references policy.document_analysis(document_id) on delete cascade,
    ordinal integer not null,
    phrase varchar(256) not null,
    direction varchar(16) not null,
    weight integer not null,
    excerpt varchar(2000) not null default '',
    constraint policy_document_evidence_pk primary key (document_id, ordinal),
    constraint policy_evidence_ordinal_ck check (ordinal between 0 and 99),
    constraint policy_evidence_phrase_ck check (btrim(phrase) <> ''),
    constraint policy_evidence_direction_ck check (direction in ('DOVISH', 'HAWKISH')),
    constraint policy_evidence_weight_ck check (weight between 1 and 10)
);

comment on schema institutional is
    'Normalized SEC 13F metadata and holdings. Raw information-table XML is stored in MinIO.';
comment on schema policy is
    'Derived analysis of official policy documents. Raw source HTML is stored in MinIO.';
