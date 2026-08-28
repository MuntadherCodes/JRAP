-- JRAP V4: structured extraction and AI gateway (SRS FR-EXT-1..6, CON-4, NFR-AI-1)
--
-- Every extracted field carries provenance: source snapshot, method (parser vs LLM
-- with prompt version), confidence, and the quoted span (FR-EXT-4). Rows below the
-- confidence threshold are flagged needs_review and enter the human-confirmation
-- queue (full review UI arrives in Phase 6). llm_call is the AI gateway's complete
-- call log — prompt version, model, input snapshots, response (NFR-AI-1).

-- ---------------------------------------------------------------- board_member (FR-EXT-1)
create table board_member (
    id             uuid primary key,
    org_id         uuid not null,
    journal_id     uuid not null references journal (id),
    audit_id       uuid not null references audit (id),
    snapshot_id    uuid not null references snapshot (id),
    name           text not null,
    normalized_name text not null,
    role           text,
    institution    text,
    country        text,
    profile_links  jsonb not null default '[]'::jsonb,
    method         text not null,           -- PARSER | LLM
    prompt_version text,                    -- when method = LLM
    confidence     numeric(3,2) not null,
    excerpt        text,                    -- the quoted span backing the extraction
    needs_review   boolean not null default false,
    created_at     timestamptz not null
);

create index board_member_audit_idx on board_member (audit_id);

-- ---------------------------------------------------------------- article (FR-EXT-2/3)
create table article (
    id                    uuid primary key,
    org_id                uuid not null,
    journal_id            uuid not null references journal (id),
    audit_id              uuid not null references audit (id),
    snapshot_id           uuid not null references snapshot (id),
    title                 text,
    title_script          text,             -- ROMAN | ARABIC | MIXED | OTHER
    doi                   text,
    pages                 text,
    abstract_text         text,
    abstract_language     text,             -- en | ar | other | unknown
    date_submitted        text,             -- as displayed; null = "not shown"
    date_accepted         text,
    date_published        text,
    keywords              jsonb not null default '[]'::jsonb,
    references_json       jsonb not null default '[]'::jsonb,
    references_count      int not null default 0,
    references_roman_share numeric(4,3),
    method                text not null,
    prompt_version        text,
    confidence            numeric(3,2) not null,
    needs_review          boolean not null default false,
    created_at            timestamptz not null
);

create index article_audit_idx on article (audit_id);
create unique index article_audit_snapshot_unique on article (audit_id, snapshot_id);
create unique index board_member_audit_snapshot_name_unique on board_member (audit_id, snapshot_id, name, role);

-- ---------------------------------------------------------------- author_slot (FR-EXT-2/6)
create table author_slot (
    id              uuid primary key,
    org_id          uuid not null,
    article_id      uuid not null references article (id),
    position        int not null,
    name            text not null,
    normalized_name text not null,
    affiliation     text,
    country         text,
    created_at      timestamptz not null
);

create index author_slot_article_idx on author_slot (article_id);

-- ---------------------------------------------------------------- llm_call (NFR-AI-1)
create table llm_call (
    id             uuid primary key,
    org_id         uuid,
    audit_id       uuid,
    prompt_name    text not null,
    prompt_version text not null,
    model          text not null,
    input_snapshot_ids jsonb not null default '[]'::jsonb,
    request_chars  int not null,
    input_tokens   int,
    output_tokens  int,
    status         text not null,           -- OK | ERROR | BUDGET_EXCEEDED
    error          text,
    response_text  text,
    created_at     timestamptz not null
);

create index llm_call_audit_idx on llm_call (audit_id);

create trigger llm_call_immutable
    before update or delete on llm_call
    for each row execute function jrap_immutable();

-- ---------------------------------------------------------------- evidence can now cite snapshots
alter table evidence_item add column snapshot_id uuid references snapshot (id);

-- ---------------------------------------------------------------- audit extraction counters
alter table audit add column articles_extracted int not null default 0;
alter table audit add column board_members_extracted int not null default 0;

-- ---------------------------------------------------------------- row-level security
alter table board_member enable row level security;
alter table article      enable row level security;
alter table author_slot  enable row level security;
alter table llm_call     enable row level security;

create policy board_member_tenant on board_member
    for all
    using (org_id = jrap_current_org() or jrap_system_access())
    with check (org_id = jrap_current_org() or jrap_system_access());

create policy article_tenant on article
    for all
    using (org_id = jrap_current_org() or jrap_system_access())
    with check (org_id = jrap_current_org() or jrap_system_access());

create policy author_slot_tenant on author_slot
    for all
    using (org_id = jrap_current_org() or jrap_system_access())
    with check (org_id = jrap_current_org() or jrap_system_access());

create policy llm_call_tenant on llm_call
    for all
    using (org_id = jrap_current_org() or jrap_system_access())
    with check (org_id = jrap_current_org() or jrap_system_access());

-- ---------------------------------------------------------------- grants
grant select, insert, update on board_member, article, author_slot to jrap_app;
grant select, insert on llm_call to jrap_app;

-- ---------------------------------------------------------------- findings become audit-linkable
alter table finding add column audit_id uuid references audit (id);
create index finding_audit_idx on finding (audit_id);
