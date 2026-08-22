create schema if not exists research;
create schema if not exists disclosure;

create table institutional.security_identity (
    cusip varchar(32) not null,
    valid_from date not null,
    valid_to date,
    ticker varchar(32) not null,
    cik varchar(10) not null default '',
    issuer varchar(512) not null,
    sector_key varchar(64) not null default '',
    confidence integer not null,
    source varchar(64) not null,
    last_seen_on date not null,
    updated_at timestamptz not null default clock_timestamp(),
    constraint institutional_security_identity_pk primary key (cusip, valid_from),
    constraint institutional_security_identity_dates_ck check (valid_to is null or valid_to >= valid_from),
    constraint institutional_security_identity_ticker_ck check (ticker ~ '^[A-Z0-9][A-Z0-9.-]{0,31}$'),
    constraint institutional_security_identity_confidence_ck check (confidence between 0 and 100),
    constraint institutional_security_identity_text_ck check (
        btrim(cusip) <> '' and btrim(issuer) <> '' and btrim(source) <> ''
    )
);

create unique index institutional_security_identity_active_uq
    on institutional.security_identity (cusip) where valid_to is null;
create index institutional_security_identity_ticker_period_idx
    on institutional.security_identity (ticker, valid_from desc, valid_to);

alter table policy.document_analysis drop constraint if exists policy_document_type_ck;
alter table policy.document_analysis add constraint policy_document_type_ck check (document_type in (
    'FOMC_STATEMENT', 'FOMC_MINUTES', 'ECONOMIC_PROJECTIONS', 'DISCOUNT_RATE_MINUTES',
    'TREASURY_RELEASE', 'TARIFF_ACTION', 'OTHER'
));
alter table policy.document_analysis drop constraint if exists policy_document_source_ck;
alter table policy.document_analysis add constraint policy_document_source_ck check (
    source_url like 'https://www.federalreserve.gov/%'
    or source_url like 'https://home.treasury.gov/%'
    or source_url like 'https://ustr.gov/%'
);

create table policy.confidence_calibration (
    document_id varchar(128) primary key references policy.document_analysis(document_id) on delete cascade,
    published_at timestamptz not null,
    raw_confidence integer not null,
    tone_score integer not null,
    actual_decision varchar(16) not null,
    direction_matched boolean not null,
    observed_at timestamptz not null default clock_timestamp(),
    constraint policy_calibration_confidence_ck check (raw_confidence between 0 and 100),
    constraint policy_calibration_tone_ck check (tone_score between -100 and 100),
    constraint policy_calibration_decision_ck check (actual_decision in ('DOVISH', 'NEUTRAL', 'HAWKISH'))
);
create index policy_confidence_calibration_published_idx
    on policy.confidence_calibration (published_at, document_id);

create table research.peer_directory (
    ticker varchar(32) primary key,
    cik varchar(10) not null,
    company_name varchar(512) not null,
    last_seen_at timestamptz not null,
    retired_on date,
    constraint peer_directory_ticker_ck check (ticker ~ '^[A-Z0-9][A-Z0-9.-]{0,31}$'),
    constraint peer_directory_cik_ck check (cik ~ '^[0-9]{10}$'),
    constraint peer_directory_name_ck check (btrim(company_name) <> '')
);

create table research.peer_taxonomy (
    ticker varchar(32) not null references research.peer_directory(ticker),
    valid_from date not null,
    valid_to date,
    cik varchar(10) not null,
    company_name varchar(512) not null,
    sic integer not null,
    sic_description varchar(512) not null,
    sector_key varchar(64) not null,
    refreshed_at timestamptz not null,
    updated_at timestamptz not null default clock_timestamp(),
    constraint peer_taxonomy_pk primary key (ticker, valid_from),
    constraint peer_taxonomy_dates_ck check (valid_to is null or valid_to >= valid_from),
    constraint peer_taxonomy_sic_ck check (sic between 100 and 9999),
    constraint peer_taxonomy_text_ck check (
        btrim(company_name) <> '' and btrim(sic_description) <> '' and btrim(sector_key) <> ''
    )
);
create unique index peer_taxonomy_active_uq
    on research.peer_taxonomy (ticker) where valid_to is null;
create index peer_taxonomy_asof_sic_idx
    on research.peer_taxonomy (sic, valid_from desc, valid_to);
create index peer_taxonomy_asof_sector_idx
    on research.peer_taxonomy (sector_key, valid_from desc, valid_to);

create table disclosure.dart_company (
    corp_code varchar(8) primary key,
    stock_code varchar(6) unique,
    corp_name varchar(512) not null,
    corp_english_name varchar(512) not null default '',
    modified_on date not null,
    collected_at timestamptz not null,
    constraint dart_company_corp_code_ck check (corp_code ~ '^[0-9]{8}$'),
    constraint dart_company_stock_code_ck check (stock_code is null or stock_code ~ '^[0-9]{6}$'),
    constraint dart_company_name_ck check (btrim(corp_name) <> '')
);

create table disclosure.dart_filing (
    receipt_number varchar(14) primary key,
    corp_code varchar(8) not null references disclosure.dart_company(corp_code),
    corp_name varchar(512) not null,
    report_name varchar(1024) not null,
    filer_name varchar(512) not null default '',
    received_on date not null,
    remark varchar(64) not null default '',
    event_type varchar(32) not null,
    source_url varchar(2048) not null,
    collected_at timestamptz not null,
    constraint dart_filing_receipt_ck check (receipt_number ~ '^[0-9]{14}$'),
    constraint dart_filing_event_ck check (event_type in (
        'MERGER_ACQUISITION', 'EXECUTIVE_CHANGE', 'CAPITAL_ACTION', 'LITIGATION',
        'RESTRUCTURING', 'EARNINGS', 'OTHER'
    )),
    constraint dart_filing_source_ck check (source_url like 'https://dart.fss.or.kr/%')
);
create index dart_filing_company_date_idx
    on disclosure.dart_filing (corp_code, received_on desc, receipt_number desc);

create table disclosure.dart_financial_metric (
    corp_code varchar(8) not null references disclosure.dart_company(corp_code),
    business_year integer not null,
    report_code varchar(8) not null,
    statement_code varchar(16) not null,
    statement_name varchar(256) not null,
    account_id varchar(512) not null,
    account_name varchar(512) not null,
    current_amount numeric(38, 4),
    previous_amount numeric(38, 4),
    currency varchar(16) not null default '',
    collected_at timestamptz not null,
    constraint dart_financial_metric_pk primary key (
        corp_code, business_year, report_code, statement_code, account_id
    ),
    constraint dart_financial_year_ck check (business_year between 1990 and 2200),
    constraint dart_financial_text_ck check (
        btrim(report_code) <> '' and btrim(statement_code) <> ''
        and btrim(statement_name) <> '' and btrim(account_id) <> '' and btrim(account_name) <> ''
    )
);
create index dart_financial_company_period_idx
    on disclosure.dart_financial_metric (corp_code, business_year desc, report_code, statement_code);

comment on table institutional.security_identity is
    'Conservative point-in-time CUSIP to ticker identity; ambiguous name matches are intentionally absent.';
comment on table policy.confidence_calibration is
    'Explicit FOMC decision labels used for causal walk-forward confidence diagnostics.';
comment on table research.peer_taxonomy is
    'SEC SIC history with forward survivorship intervals; raw submissions are kept in MinIO.';
comment on schema disclosure is
    'Normalized Korean OpenDART company, material-event, and financial facts; raw payloads are kept in MinIO.';
