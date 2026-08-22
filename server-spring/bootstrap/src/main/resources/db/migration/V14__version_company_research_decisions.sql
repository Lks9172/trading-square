alter table company.research_summary
    add column if not exists execution_action varchar(16) not null default 'HOLD';

alter table company.research_summary
    add column if not exists calculation_version integer not null default 1;

create index if not exists idx_company_research_summary_current_version
    on company.research_summary (calculation_version, ticker);

comment on column company.research_summary.calculation_version is
    'Version of the score/signal calculation contract. Readers fail closed on older versions.';

comment on column company.research_summary.execution_action is
    'Authoritative current investment-decision action, not a buy-score label.';
