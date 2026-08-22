alter table company.analyst_snapshot
    add column eps_revision_7d_pct double precision,
    add column eps_revision_30d_pct double precision,
    add column eps_revision_90d_pct double precision;

alter table company.analyst_snapshot
    add constraint analyst_eps_revision_finite_ck check (
        (eps_revision_7d_pct is null or eps_revision_7d_pct not in (
            'NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision
        ))
        and (eps_revision_30d_pct is null or eps_revision_30d_pct not in (
            'NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision
        ))
        and (eps_revision_90d_pct is null or eps_revision_90d_pct not in (
            'NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision
        ))
    );

create index analyst_snapshot_revision_latest_idx
    on company.analyst_snapshot (ticker, observed_on desc)
    include (eps_revision_30d_pct, collected_at);

comment on column company.analyst_snapshot.eps_revision_30d_pct is
    'Provider forward-EPS estimate change versus the provider 30-day-ago estimate, observed on observed_on.';
