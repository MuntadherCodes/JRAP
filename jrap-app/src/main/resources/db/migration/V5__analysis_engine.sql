-- JRAP V5: analysis engine results (SRS FR-ANL-1..12, §5)
--
-- The engine is deterministic: same evidence set + rubric version => identical rows.
-- Gateway checks, CSAB scores and metrics live here; red flags land in the existing
-- finding table (category 'red-flag', audit-linked, evidence-linked). The audit row
-- freezes rubric_version and detector_versions when ANALYSE starts (§3.3).

-- ---------------------------------------------------------------- gateway_check (FR-ANL-1, §5.1)
create table gateway_check (
    id                uuid primary key,
    org_id            uuid not null,
    audit_id          uuid not null references audit (id),
    journal_id        uuid not null references journal (id),
    code              text not null,        -- G1..G6
    outcome           text not null,        -- PASS | PASS_WITH_CAVEATS | FAIL | UNCLEAR
    summary           text not null,
    evidence_item_ids jsonb not null default '[]'::jsonb,
    created_at        timestamptz not null
);

create unique index gateway_check_audit_code_unique on gateway_check (audit_id, code);

-- ---------------------------------------------------------------- csab_score (FR-ANL-5, §5.2)
create table csab_score (
    id         uuid primary key,
    org_id     uuid not null,
    audit_id   uuid not null references audit (id),
    journal_id uuid not null references journal (id),
    category   text not null,               -- policy | content | standing | regularity | availability
    score      int  not null,               -- 0..5
    criteria   jsonb not null default '[]'::jsonb, -- [{code, met, delta, detail}]
    created_at timestamptz not null
);

create unique index csab_score_audit_category_unique on csab_score (audit_id, category);

-- ---------------------------------------------------------------- analysis_metric (FR-ANL-2/3/4)
create table analysis_metric (
    id         uuid primary key,
    org_id     uuid not null,
    audit_id   uuid not null references audit (id),
    name       text not null,               -- e.g. author_country_hhi, citation_trend
    value      numeric,
    detail     jsonb not null default '{}'::jsonb,
    created_at timestamptz not null
);

create unique index analysis_metric_audit_name_unique on analysis_metric (audit_id, name);

-- ---------------------------------------------------------------- row-level security
alter table gateway_check   enable row level security;
alter table csab_score      enable row level security;
alter table analysis_metric enable row level security;

create policy gateway_check_tenant on gateway_check
    for all
    using (org_id = jrap_current_org() or jrap_system_access())
    with check (org_id = jrap_current_org() or jrap_system_access());

create policy csab_score_tenant on csab_score
    for all
    using (org_id = jrap_current_org() or jrap_system_access())
    with check (org_id = jrap_current_org() or jrap_system_access());

create policy analysis_metric_tenant on analysis_metric
    for all
    using (org_id = jrap_current_org() or jrap_system_access())
    with check (org_id = jrap_current_org() or jrap_system_access());

-- ---------------------------------------------------------------- grants
grant select, insert on gateway_check, csab_score, analysis_metric to jrap_app;
