alter table research.peer_directory
    add column if not exists taxonomy_checked_at timestamp with time zone;

create index if not exists peer_directory_taxonomy_checked_idx
    on research.peer_directory (taxonomy_checked_at nulls first, ticker)
    where retired_on is null;
