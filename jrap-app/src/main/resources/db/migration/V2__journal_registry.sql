-- JRAP V2: journal registry, core finding/evidence domain, source-API cache, quotas
-- (SRS FR-JRN-1..3, FR-INT-1..4/6, §3.3 data model)
--
-- Tenant tables follow the V1 row-level-security pattern (org_id + jrap_current_org()).
-- api_record is deliberately GLOBAL: it caches public scholarly-API responses shared by
-- every tenant (CON-3), holds no tenant data, and is write-once (immutability trigger).

-- ---------------------------------------------------------------- api_record (global, write-once)
create table api_record (
    id            uuid primary key,
    source        text not null,               -- OPENALEX | CROSSREF | DOAJ | ISSN_PORTAL | SITE
    request_key   text not null,               -- e.g. 'OPENALEX:sources:issn:2708-9134'
    request_url   text not null,
    status_code   int  not null,
    response_body text,
    content_hash  text,
    retrieved_at  timestamptz not null,
    expires_at    timestamptz not null
);

create index api_record_lookup_idx on api_record (source, request_key, retrieved_at desc);

-- Generic immutability guard naming the table it protects.
create function jrap_immutable() returns trigger
language plpgsql as
$$
begin
    raise exception '% is immutable (write-once, SRS §3.3)', tg_table_name;
end;
$$;

create trigger api_record_immutable
    before update or delete on api_record
    for each row execute function jrap_immutable();

-- ---------------------------------------------------------------- journal
create table journal (
    id               uuid primary key,
    org_id           uuid not null references organisation (id),
    status           text not null,            -- ACTIVE | ARCHIVED
    registered_input text not null,            -- the ISSN or URL the user supplied
    title            text,
    title_variants   jsonb not null default '[]'::jsonb,
    publisher        text,
    country          text,
    issn_l           text,
    issn_print       text,
    issn_online      text,
    doi_prefix       text,
    platform         text,                     -- e.g. 'OJS 3.3.0-8' when detectable
    homepage_url     text,
    openalex_id      text,
    doaj_id          text,
    in_crossref      boolean not null default false,
    in_doaj          boolean not null default false,
    created_at       timestamptz not null,
    archived_at      timestamptz
);

create index journal_org_idx on journal (org_id);
-- One active registration of a journal per organisation (by linking ISSN when known).
create unique index journal_org_issnl_unique on journal (org_id, issn_l) where issn_l is not null and status = 'ACTIVE';

-- ---------------------------------------------------------------- journal_identity_record (per source)
create table journal_identity_record (
    id            uuid primary key,
    org_id        uuid not null,
    journal_id    uuid not null references journal (id),
    source        text not null,               -- OPENALEX | CROSSREF | DOAJ | ISSN_PORTAL | SITE
    availability  text not null,               -- OK | NOT_FOUND | UNAVAILABLE
    api_record_id uuid references api_record (id),
    title         text,
    publisher     text,
    country       text,
    issn_print    text,
    issn_online   text,
    issn_l        text,
    extra         jsonb not null default '{}'::jsonb,
    retrieved_at  timestamptz not null
);

create index journal_identity_journal_idx on journal_identity_record (journal_id);

-- ---------------------------------------------------------------- finding
-- Core finding envelope (FR-ANL-12 shape, introduced early for FR-JRN-2 identity findings).
-- audit_id arrives with the pipeline in Phase 3; registry findings hang off the journal.
create table finding (
    id               uuid primary key,
    org_id           uuid not null,
    journal_id       uuid not null references journal (id),
    category         text not null,            -- 'identity' in Phase 2
    code             text not null,            -- e.g. IDENTITY_PUBLISHER_MISMATCH
    severity         text not null,            -- CRITICAL | HIGH | MEDIUM | LOW | INFO
    status           text not null,            -- AUTO | CONFIRMED | REJECTED | NEEDS_VERIFICATION
    title            text not null,
    description      text not null,
    detector_version text not null,
    created_at       timestamptz not null
);

create index finding_journal_idx on finding (journal_id);

-- ---------------------------------------------------------------- evidence
create table evidence_item (
    id            uuid primary key,
    org_id        uuid not null,
    journal_id    uuid not null references journal (id),
    type          text not null,               -- API_RECORD (Phase 2) | SNAPSHOT | MANUAL | COMPUTED
    api_record_id uuid references api_record (id),
    source        text not null,
    excerpt       text,                        -- the quoted span backing the finding
    retrieved_at  timestamptz not null,
    created_at    timestamptz not null
);

create index evidence_item_journal_idx on evidence_item (journal_id);

create table evidence_link (
    finding_id       uuid not null references finding (id),
    evidence_item_id uuid not null references evidence_item (id),
    org_id           uuid not null,
    primary key (finding_id, evidence_item_id)
);

-- ---------------------------------------------------------------- org_quota (admin-set; beta has no billing)
create table org_quota (
    org_id       uuid primary key references organisation (id),
    max_journals int not null,
    updated_at   timestamptz not null
);

-- ---------------------------------------------------------------- row-level security
alter table journal                 enable row level security;
alter table journal_identity_record enable row level security;
alter table finding                 enable row level security;
alter table evidence_item           enable row level security;
alter table evidence_link           enable row level security;
alter table org_quota               enable row level security;

create policy journal_tenant on journal
    for all
    using (org_id = jrap_current_org() or jrap_system_access())
    with check (org_id = jrap_current_org() or jrap_system_access());

create policy journal_identity_tenant on journal_identity_record
    for all
    using (org_id = jrap_current_org() or jrap_system_access())
    with check (org_id = jrap_current_org() or jrap_system_access());

create policy finding_tenant on finding
    for all
    using (org_id = jrap_current_org() or jrap_system_access())
    with check (org_id = jrap_current_org() or jrap_system_access());

create policy evidence_item_tenant on evidence_item
    for all
    using (org_id = jrap_current_org() or jrap_system_access())
    with check (org_id = jrap_current_org() or jrap_system_access());

create policy evidence_link_tenant on evidence_link
    for all
    using (org_id = jrap_current_org() or jrap_system_access())
    with check (org_id = jrap_current_org() or jrap_system_access());

create policy org_quota_tenant on org_quota
    for all
    using (org_id = jrap_current_org() or jrap_system_access())
    with check (org_id = jrap_current_org() or jrap_system_access());

-- ---------------------------------------------------------------- grants
grant select, insert, update on journal, journal_identity_record, finding, evidence_item, org_quota to jrap_app;
grant select, insert, delete on evidence_link to jrap_app;
grant select, insert on api_record to jrap_app;
