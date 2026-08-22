-- Keep terminal delivery history queryable for its configured audit window
-- without allowing the transactional outbox to grow forever.
create index notification_outbox_terminal_retention_idx
    on notification.outbox ((coalesce(delivered_at, available_at)), id)
    where status in ('DELIVERED', 'DEAD');

-- The 45-day narrative health view scans every theme by observation date.
-- The existing latest index starts with theme/source and cannot serve it.
create index narrative_source_observation_date_idx
    on research.narrative_source_observation (
        observation_date desc, theme_id, source_key, revision desc, observed_at desc
    );

-- SIC expansion uses both two- and three-digit industry groups. Expression
-- indexes let PostgreSQL combine those alternatives rather than scanning the
-- complete point-in-time taxonomy as the universe grows.
create index peer_taxonomy_industry_group_idx
    on research.peer_taxonomy ((sic / 10), valid_from desc, valid_to);
create index peer_taxonomy_major_group_idx
    on research.peer_taxonomy ((sic / 100), valid_from desc, valid_to);

-- OpenDART freshness reads max(collected_at) across each projection table.
create index dart_company_collected_idx
    on disclosure.dart_company (collected_at desc);
create index dart_filing_collected_idx
    on disclosure.dart_filing (collected_at desc);
create index dart_financial_collected_idx
    on disclosure.dart_financial_metric (collected_at desc);

comment on index notification.notification_outbox_terminal_retention_idx is
    'Supports bounded deletion of DELIVERED/DEAD outbox audit rows.';
