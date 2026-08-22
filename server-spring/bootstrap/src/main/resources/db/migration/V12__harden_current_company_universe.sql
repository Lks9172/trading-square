-- Keep immutable cutover projections from leaking renamed/retired symbols into
-- current reads, and enforce fail-closed score persistence for stale filings.

INSERT INTO company.analyst_series_state (ticker, updated_at)
SELECT 'MRSH', updated_at
FROM company.analyst_series_state
WHERE ticker = 'MMC'
ON CONFLICT (ticker) DO UPDATE
SET updated_at = GREATEST(company.analyst_series_state.updated_at, EXCLUDED.updated_at);

INSERT INTO company.analyst_snapshot (
    ticker, observed_on, analyst_score, upside_pct, collected_at
)
SELECT 'MRSH', observed_on, analyst_score, upside_pct, collected_at
FROM company.analyst_snapshot
WHERE ticker = 'MMC'
ON CONFLICT (ticker, observed_on) DO UPDATE
SET analyst_score = EXCLUDED.analyst_score,
    upside_pct = EXCLUDED.upside_pct,
    collected_at = EXCLUDED.collected_at
WHERE EXCLUDED.collected_at >= company.analyst_snapshot.collected_at;

DELETE FROM company.analyst_series_state WHERE ticker = 'MMC';

UPDATE company.research_summary
SET ticker = 'MRSH'
WHERE ticker = 'MMC'
  AND NOT EXISTS (
      SELECT 1 FROM company.research_summary current_symbol WHERE current_symbol.ticker = 'MRSH'
  );
DELETE FROM company.research_summary WHERE ticker = 'MMC';

-- Electronic Arts completed its take-private merger on 2026-08-04 and Nasdaq
-- suspended the common stock before the 2026-08-05 open. CTRA was already
-- retired by application policy; remove either snapshot if a prior runtime
-- persisted it as a currently investable company.
DELETE FROM company.research_summary WHERE ticker IN ('EA', 'CTRA');

UPDATE company.research_summary
SET total_score = NULL,
    growth_score = NULL,
    quality_score = NULL,
    valuation_score = NULL,
    balance_sheet_score = NULL,
    buy_score = NULL,
    buy_label = NULL,
    appeal_score = NULL,
    crowding_score = NULL
WHERE fundamentals_status <> 'CURRENT';

ALTER TABLE company.research_summary
    ADD CONSTRAINT company_research_summary_fresh_score_ck CHECK (
        fundamentals_status = 'CURRENT'
        OR (
            total_score IS NULL
            AND growth_score IS NULL
            AND quality_score IS NULL
            AND valuation_score IS NULL
            AND balance_sheet_score IS NULL
            AND buy_score IS NULL
            AND buy_label IS NULL
            AND appeal_score IS NULL
            AND crowding_score IS NULL
        )
    );
