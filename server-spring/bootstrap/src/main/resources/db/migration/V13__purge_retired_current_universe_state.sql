-- Historical institutional identities remain valid for old 13-F filings, but
-- retired equities must not survive in current analyst or peer projections.
-- The old Nest runtime could repopulate these rows after V12 ran during the
-- rolling migration; it is now detached, so this final cleanup is stable.

DELETE FROM research.peer_taxonomy WHERE ticker IN ('EA', 'CTRA');
DELETE FROM research.peer_directory WHERE ticker IN ('EA', 'CTRA');
DELETE FROM company.research_summary WHERE ticker IN ('EA', 'CTRA');

-- analyst_snapshot rows are deleted by the series-state foreign key cascade.
DELETE FROM company.analyst_series_state WHERE ticker IN ('EA', 'CTRA');
