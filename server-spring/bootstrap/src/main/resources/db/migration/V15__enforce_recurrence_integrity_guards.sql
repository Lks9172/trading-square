-- Persisted last-line guards for previously observed score/action incidents.
-- Application policy remains authoritative; these constraints prevent a future
-- regression from committing stale BUY evidence even if that policy is bypassed.

alter table company.research_summary
    drop constraint if exists company_research_summary_fundamentals_status_ck;

alter table company.research_summary
    add constraint company_research_summary_fundamentals_status_ck check (
        fundamentals_status in (
            'CURRENT', 'LAGGING', 'INCOMPLETE', 'PENDING', 'UNAVAILABLE', 'UNKNOWN'
        )
    ),
    add constraint company_research_summary_execution_action_ck check (
        execution_action in ('STRONG BUY', 'BUY', 'HOLD', 'REDUCE', 'SELL')
    ),
    add constraint company_research_summary_price_signal_completeness_ck check (
        num_nonnulls(
            price_bottom_score,
            volume_confirmation_score,
            failure_risk_score,
            confirmed_bottom_score,
            confirmed_bottom_state
        ) in (0, 5)
    ),
    add constraint company_research_summary_score_bundle_ck check (
        num_nonnulls(
            total_score, growth_score, quality_score, valuation_score,
            balance_sheet_score, buy_score, appeal_score, crowding_score
        ) in (0, 8)
        and ((total_score is null) = (buy_label is null))
    ),
    add constraint company_research_summary_score_evidence_ck check (
        total_score is null or (fundamentals_status = 'CURRENT' and valuation_eligible)
    ),
    add constraint company_research_summary_buy_evidence_ck check (
        execution_action not in ('STRONG BUY', 'BUY')
        or (
            fundamentals_status = 'CURRENT'
            and valuation_eligible
            and total_score is not null
            and growth_score is not null
            and quality_score is not null
            and valuation_score is not null
            and balance_sheet_score is not null
            and buy_score is not null
            and price_bottom_score is not null
            and volume_confirmation_score is not null
            and failure_risk_score is not null
            and confirmed_bottom_score is not null
            and confirmed_bottom_state is not null
        )
    ),
    add constraint company_research_summary_calculation_version_ck check (
        calculation_version > 0
    );

alter table company.analyst_snapshot
    add constraint analyst_upside_plausibility_ck check (
        upside_pct is null or upside_pct between -100 and 1000
    );

-- The SEC parser intentionally drops zero-value/zero-share rows. Preserve the
-- same contract at the final persistence boundary so a future parser change
-- cannot silently reintroduce unusable holdings into flow calculations.
alter table institutional.holding
    drop constraint if exists institutional_holding_value_ck;

alter table institutional.holding
    add constraint institutional_holding_value_ck check (
        value_usd > 0
        and shares > 0
        and value_usd not in (
            'NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision
        )
        and shares not in (
            'NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision
        )
    );

alter table notification.delivery_state
    add column integrity_fingerprint varchar(64) not null default '';

alter table notification.delivery_state
    add constraint notification_integrity_fingerprint_ck check (
        integrity_fingerprint = '' or integrity_fingerprint ~ '^[0-9a-f]{64}$'
    );

comment on column notification.delivery_state.integrity_fingerprint is
    'Active data-integrity incident fingerprint. Clearing it marks recovery and allows the same incident to alert again.';
