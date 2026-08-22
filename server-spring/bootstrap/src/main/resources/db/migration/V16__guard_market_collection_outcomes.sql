-- A collection is successful only when every accepted observation reached the
-- owned store and no failure metadata remains. This is a final persistence
-- guard for the previously possible collected>0/persisted=0 false-success.

alter table market.collection_status
    add constraint market_collection_status_outcome_consistency_ck check (
        status <> 'SUCCESS'
        or (
            collected_count > 0
            and persisted_count = collected_count
            and btrim(failure_keys) = ''
            and btrim(failure_type) = ''
        )
    );

comment on constraint market_collection_status_outcome_consistency_ck
    on market.collection_status is
    'Prevents a provider response or partial persistence result from being recorded as a successful collection.';
