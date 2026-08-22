alter table company.research_summary
    add column if not exists macd_timing jsonb;

alter table company.research_summary
    add constraint company_research_summary_macd_timing_ck check (
        macd_timing is null or jsonb_typeof(macd_timing) = 'object'
    );

comment on column company.research_summary.macd_timing is
    'Current daily/weekly MACD cross, histogram and confirmed-divergence evidence used by bounded notification projections; not a buy/sell decision.';

alter table notification.candidate_snapshot
    add column if not exists macd_timing jsonb;

alter table notification.candidate_snapshot
    add constraint notification_candidate_snapshot_macd_timing_ck check (
        macd_timing is null or jsonb_typeof(macd_timing) = 'object'
    );

comment on column notification.candidate_snapshot.macd_timing is
    'Notification-owned compact daily/weekly MACD evidence retained for startup and strengthening messages.';
