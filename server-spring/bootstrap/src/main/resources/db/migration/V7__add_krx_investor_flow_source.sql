-- The KRX category contains investor-flow observations whose concrete provider
-- is recorded separately as NAVER_FINANCE in provider_code. Keep the database
-- source constraint aligned with the domain enum before the collector starts.
alter table market.observation
    drop constraint market_observation_source_ck;

alter table market.observation
    add constraint market_observation_source_ck check (source in (
        'FRED', 'YAHOO', 'FEAR_GREED', 'SENTIMENT', 'STABLECOIN', 'KRX'
    )) not valid;

alter table market.observation
    validate constraint market_observation_source_ck;

comment on constraint market_observation_source_ck on market.observation is
    'Bounded market source categories; KRX provider provenance remains in provider_code.';
