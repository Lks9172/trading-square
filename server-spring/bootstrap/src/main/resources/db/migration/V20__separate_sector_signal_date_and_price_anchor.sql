alter table research.sector_rotation_run
    add column price_anchor_on date;

-- V19 made the ledger immutable before these two financial dates were split.
-- Flyway owns this table; suspend only the V19 update guard for the bounded,
-- transactional backfill and restore it immediately afterwards.
alter table research.sector_rotation_run
    disable trigger sector_rotation_run_immutable_trg;

update research.sector_rotation_run
set price_anchor_on = as_of_date
where price_anchor_on is null;

alter table research.sector_rotation_run
    enable trigger sector_rotation_run_immutable_trg;

alter table research.sector_rotation_run
    alter column price_anchor_on set not null;

alter table research.sector_rotation_run
    drop constraint sector_rotation_run_session_uk;

alter table research.sector_rotation_run
    add constraint sector_rotation_run_price_session_uk
        unique (methodology_version, price_anchor_on);

alter table research.sector_rotation_run
    add constraint sector_rotation_run_price_date_ck
        check (price_anchor_on <= as_of_date);

drop index research.sector_rotation_run_asof_idx;
create index sector_rotation_run_asof_idx
    on research.sector_rotation_run (price_anchor_on desc, methodology_version)
    include (as_of_date, calculated_at);

comment on column research.sector_rotation_run.as_of_date is
    'UTC financial signal date on which all component evidence was available.';
comment on column research.sector_rotation_run.price_anchor_on is
    'Latest safely completed common SPY plus eleven-sector total-return session used as the forward-return start.';
