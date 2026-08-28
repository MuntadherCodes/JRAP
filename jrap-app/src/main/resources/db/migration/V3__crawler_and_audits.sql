-- JRAP V3: audits, crawl frontier, snapshot store metadata (SRS FR-CRWL-1..7, §3.3)
--
-- The Audit is one complete evaluation run of one journal (SRS §1.3). Its pipeline is
-- checkpointed per stage so an interrupted audit resumes, never restarts (NFR-AVL-1).
-- Snapshot rows are write-once (§3.3); raw bytes live in object storage behind the
-- SnapshotStore interface (filesystem in dev/test, S3-compatible in production).

-- ---------------------------------------------------------------- audit
create table audit (
    id             uuid primary key,
    org_id         uuid not null references organisation (id),
    journal_id     uuid not null references journal (id),
    status         text not null,      -- PENDING | RUNNING | COMPLETE | FAILED | CANCELLED
    stage          text not null,      -- CRAWL (Phase 3) | EXTRACT | ENRICH | ANALYSE | ... later
    page_cap       int  not null,      -- FR-CRWL-4 / FR-BILL-2 mechanism (plan default 3000)
    pages_fetched  int  not null default 0,
    pages_skipped  int  not null default 0,
    error          text,
    created_by     uuid,
    created_at     timestamptz not null,
    started_at     timestamptz,
    finished_at    timestamptz,
    -- §3.3: an audit freezes the versions it used (filled by later phases)
    rubric_version text,
    detector_versions jsonb not null default '{}'::jsonb
);

create index audit_journal_idx on audit (journal_id, created_at desc);
create index audit_claim_idx on audit (status) where status in ('PENDING', 'RUNNING');

-- ---------------------------------------------------------------- crawl_task (frontier)
create table crawl_task (
    id              uuid primary key,
    org_id          uuid not null,
    audit_id        uuid not null references audit (id),
    url             text not null,
    status          text not null,     -- QUEUED | DONE | SKIPPED | FAILED
    skip_reason     text,              -- FR-CRWL-4: every skipped/blocked URL records why
    depth           int not null default 0,
    discovered_from text,
    fetched_at      timestamptz,
    created_at      timestamptz not null
);

create unique index crawl_task_audit_url_unique on crawl_task (audit_id, url);
create index crawl_task_claim_idx on crawl_task (audit_id, status) where status = 'QUEUED';

-- ---------------------------------------------------------------- snapshot (write-once)
create table snapshot (
    id               uuid primary key,
    org_id           uuid not null,
    audit_id         uuid not null references audit (id),
    journal_id       uuid not null references journal (id),
    url              text not null,
    http_status      int  not null,
    content_type     text,
    content_hash     text not null,
    raw_storage_key  text not null,    -- raw bytes in the snapshot store
    text_storage_key text,             -- normalised text (HTML text / PDF text layer)
    page_type        text not null,    -- FR-CRWL-1 classification (SRS taxonomy) or 'other'
    headers          jsonb not null default '{}'::jsonb,
    fetched_at       timestamptz not null,
    created_at       timestamptz not null
);

create index snapshot_audit_idx on snapshot (audit_id, page_type);
create unique index snapshot_audit_url_unique on snapshot (audit_id, url);

create trigger snapshot_immutable
    before update or delete on snapshot
    for each row execute function jrap_immutable();

-- ---------------------------------------------------------------- oai_harvest (FR-CRWL-2 cross-check)
create table oai_harvest (
    id           uuid primary key,
    org_id       uuid not null,
    audit_id     uuid not null references audit (id),
    identifier   text not null,
    datestamp    text,
    title        text,
    created_at   timestamptz not null
);

create unique index oai_harvest_audit_identifier_unique on oai_harvest (audit_id, identifier);

-- ---------------------------------------------------------------- row-level security
alter table audit       enable row level security;
alter table crawl_task  enable row level security;
alter table snapshot    enable row level security;
alter table oai_harvest enable row level security;

create policy audit_tenant on audit
    for all
    using (org_id = jrap_current_org() or jrap_system_access())
    with check (org_id = jrap_current_org() or jrap_system_access());

create policy crawl_task_tenant on crawl_task
    for all
    using (org_id = jrap_current_org() or jrap_system_access())
    with check (org_id = jrap_current_org() or jrap_system_access());

create policy snapshot_tenant on snapshot
    for all
    using (org_id = jrap_current_org() or jrap_system_access())
    with check (org_id = jrap_current_org() or jrap_system_access());

create policy oai_harvest_tenant on oai_harvest
    for all
    using (org_id = jrap_current_org() or jrap_system_access())
    with check (org_id = jrap_current_org() or jrap_system_access());

-- ---------------------------------------------------------------- grants
grant select, insert, update on audit, crawl_task to jrap_app;
grant select, insert on snapshot, oai_harvest to jrap_app;
