alter table company.research_summary
    add column fundamentals_status varchar(16) not null default 'UNKNOWN',
    add column latest_periodic_report_date date,
    add column latest_periodic_filing_date date,
    add column latest_periodic_form varchar(16),
    add column fundamentals_lag_days integer,
    add column score_warnings jsonb not null default '[]'::jsonb;

alter table company.research_summary
    add constraint company_research_summary_fundamentals_status_ck check (
        fundamentals_status in ('CURRENT', 'LAGGING', 'INCOMPLETE', 'UNKNOWN')
    ),
    add constraint company_research_summary_fundamentals_lag_ck check (
        fundamentals_lag_days is null or fundamentals_lag_days >= 0
    ),
    add constraint company_research_summary_score_warnings_ck check (
        jsonb_typeof(score_warnings) = 'array'
    );

comment on column company.research_summary.fundamentals_status is
    'Comparison of normalized facts period against the newest filed periodic report.';
