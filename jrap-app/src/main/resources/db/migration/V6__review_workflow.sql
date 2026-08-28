-- JRAP V6: human review workflow (SRS FR-REV-1..4, FR-INT-7, CON-6)
--
-- review_decision is the append-only log of every analyst action (FR-REV-1: "every
-- action is logged with user and timestamp"): confirmations, rejections, severity
-- edits, annotations, exclusions, extraction corrections, manual-evidence attachment.
-- Rows are immutable; the current state they produce lives on the target rows
-- (finding.status/severity, board_member/article needs_review).

-- ---------------------------------------------------------------- review_decision (FR-REV-1)
create table review_decision (
    id               uuid primary key,
    org_id           uuid not null,
    audit_id         uuid not null references audit (id),
    target_type      text not null,        -- FINDING | BOARD_MEMBER | ARTICLE | EVIDENCE
    target_id        uuid not null,
    action           text not null,        -- CONFIRM | REJECT | EDIT_SEVERITY | ANNOTATE
                                           -- | EXCLUDE | INCLUDE | CORRECT | ATTACH_EVIDENCE
    reason           text,                 -- required for REJECT and EXCLUDE (enforced in service)
    old_value        jsonb,
    new_value        jsonb,
    decided_by       uuid not null,        -- app_user id
    decided_by_email text not null,        -- denormalised so history survives user removal
    created_at       timestamptz not null
);

create index review_decision_audit_idx on review_decision (audit_id, created_at);
create index review_decision_target_idx on review_decision (target_id);

create trigger review_decision_immutable
    before update or delete on review_decision
    for each row execute function jrap_immutable();

-- ---------------------------------------------------------------- finding review state (FR-REV-1/4)
-- status (auto/confirmed/rejected/needs-verification) already exists (FR-ANL-12).
-- excluded: FR-REV-4 — an analyst may explicitly exclude a needs-verification finding
-- from release; exclusions are listed in the report annex (Phase 7).
alter table finding add column excluded boolean not null default false;
alter table finding add column exclusion_reason text;
alter table finding add column review_note text;
alter table finding add column reviewed_by uuid;
alter table finding add column reviewed_at timestamptz;

-- ---------------------------------------------------------------- manual evidence payloads (FR-INT-7)
-- Analyst-supplied artefacts (screenshots, exports) become first-class evidence items;
-- their bytes live in the snapshot store under these keys.
alter table evidence_item add column storage_key text;
alter table evidence_item add column content_type text;
alter table evidence_item add column audit_id uuid references audit (id);
alter table evidence_item add column uploaded_by uuid;

-- ---------------------------------------------------------------- row-level security
alter table review_decision enable row level security;

create policy review_decision_tenant on review_decision
    for all
    using (org_id = jrap_current_org() or jrap_system_access())
    with check (org_id = jrap_current_org() or jrap_system_access());

-- ---------------------------------------------------------------- grants
grant select, insert on review_decision to jrap_app;
