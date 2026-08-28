-- JRAP V7: audit reports (SRS FR-RPT-1..7, CON-5, CON-7, NFR-LEG-1)
--
-- A report is a versioned, structured document generated from one audit's reviewed
-- evidence. Content is stored as structured sentences, each carrying its finding and
-- evidence citations — the renderer cannot emit a factual sentence without them
-- (CON-5), and the FR-RPT-4 guard result is persisted per sentence. Draft reports are
-- editable; RELEASED reports are frozen by trigger and hash-stamped.

create table report (
    id                       uuid primary key,
    org_id                   uuid not null,
    audit_id                 uuid not null references audit (id),
    journal_id               uuid not null references journal (id),
    version                  int  not null,        -- per-audit, 1..n (FR-RPT-1)
    status                   text not null,        -- DRAFT | RELEASED
    verdict                  text not null,        -- READY | CONDITIONAL | NOT_READY
    sections                 jsonb not null default '[]'::jsonb,
    roadmap                  jsonb not null default '[]'::jsonb,  -- FR-RPT-6 (§5.4)
    guard_report             jsonb not null default '{}'::jsonb,  -- FR-RPT-4 per-sentence outcome
    guard_passed             boolean not null default false,
    exclusions               jsonb not null default '[]'::jsonb,  -- FR-REV-4 annex
    narrative_prompt_version text,                 -- FR-RPT-2 provenance when LLM-drafted
    content_hash             text,                 -- SHA-256 of canonical content at release
    created_by               uuid,
    created_at               timestamptz not null,
    released_by              uuid,
    released_at              timestamptz
);

create unique index report_audit_version_unique on report (audit_id, version);
create index report_journal_idx on report (journal_id, created_at desc);

-- Released reports are immutable (FR-RPT-5); drafts remain editable.
create function jrap_released_report_immutable() returns trigger
language plpgsql as
$$
begin
    if old.status = 'RELEASED' then
        raise exception 'report % is released and immutable (FR-RPT-5)', old.id;
    end if;
    if tg_op = 'DELETE' then
        return old;
    end if;
    return new;
end;
$$;

create trigger report_release_guard
    before update or delete on report
    for each row execute function jrap_released_report_immutable();

-- ---------------------------------------------------------------- row-level security
alter table report enable row level security;

create policy report_tenant on report
    for all
    using (org_id = jrap_current_org() or jrap_system_access())
    with check (org_id = jrap_current_org() or jrap_system_access());

-- ---------------------------------------------------------------- grants
grant select, insert, update on report to jrap_app;
