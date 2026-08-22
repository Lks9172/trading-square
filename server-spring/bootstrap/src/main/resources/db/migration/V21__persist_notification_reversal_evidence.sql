alter table company.research_summary
    add column if not exists confirmed_bottom_signal_date date,
    add column if not exists reversal_status varchar(16),
    add column if not exists reversal_score integer,
    add column if not exists price_signal_reasons jsonb not null default '[]'::jsonb;

alter table company.research_summary
    add constraint company_research_summary_reversal_status_ck check (
        reversal_status is null or reversal_status in ('OFF', 'EARLY', 'ON', 'STRONG')
    ),
    add constraint company_research_summary_reversal_score_ck check (
        reversal_score is null or reversal_score between 0 and 100
    ),
    add constraint company_research_summary_price_signal_reasons_ck check (
        jsonb_typeof(price_signal_reasons) = 'array'
    );

comment on column company.research_summary.confirmed_bottom_signal_date is
    'Current bottom-event date persisted for notification rendering without recalculating the full chart universe.';

comment on column company.research_summary.reversal_status is
    'Current OFF/EARLY/ON/STRONG reversal confirmation produced by the company price-signal stack.';

comment on column company.research_summary.reversal_score is
    'Current reversal confirmation score; descriptive evidence, not a calibrated probability.';

comment on column company.research_summary.price_signal_reasons is
    'Bounded current price-signal explanations used by notification projections.';
