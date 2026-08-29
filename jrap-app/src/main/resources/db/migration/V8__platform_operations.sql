-- JRAP V8: platform operations (SRS FR-AUTH-4, FR-DASH-1..4, FR-ADM-1/2, §3.2.2)
--
-- The public-API surface (scoped API keys, webhooks with delivery log), the day-to-day
-- operating layer (action tracking, scheduled re-audits), and global admin settings
-- (feature flags, crawl blocklist, rubric version override). Everything org-scoped is
-- under row-level security; app_setting is deliberately global (platform-admin managed,
-- guarded at the API layer).

-- ---------------------------------------------------------------- api_key (FR-AUTH-4)
create table api_key (
    id                    uuid primary key,
    org_id                uuid not null,
    name                  text not null,
    prefix                text not null,        -- display fragment ("jrap_ab12…")
    key_hash              text not null,        -- SHA-256 of the full secret; secret never stored
    scopes                jsonb not null default '["read"]'::jsonb,  -- read | write
    rate_limit_per_minute int  not null default 60,
    created_by            uuid not null,
    created_at            timestamptz not null,
    last_used_at          timestamptz,
    revoked_at            timestamptz
);

create unique index api_key_hash_unique on api_key (key_hash);
create index api_key_org_idx on api_key (org_id);

-- ---------------------------------------------------------------- webhook (§3.2.2)
create table webhook (
    id               uuid primary key,
    org_id           uuid not null,
    url              text not null,
    secret           text not null,            -- HMAC-SHA256 signing key
    events           jsonb not null default '["audit.completed"]'::jsonb,
    active           boolean not null default true,
    created_at       timestamptz not null,
    last_status      int,
    last_delivery_at timestamptz
);

create index webhook_org_idx on webhook (org_id);

create table webhook_delivery (
    id           uuid primary key,
    org_id       uuid not null,
    webhook_id   uuid not null references webhook (id),
    event        text not null,
    payload      jsonb not null,
    status_code  int,
    ok           boolean not null default false,
    attempted_at timestamptz not null
);

create index webhook_delivery_webhook_idx on webhook_delivery (webhook_id, attempted_at desc);

-- ---------------------------------------------------------------- action_item (FR-DASH-1/2)
create table action_item (
    id                     uuid primary key,
    org_id                 uuid not null,
    journal_id             uuid not null references journal (id),
    report_id              uuid references report (id),
    catalogue_action_id    text not null,       -- id from roadmap/catalogue (§5.4)
    title                  text not null,
    description            text not null,
    phase                  text not null,       -- P0_3 | P3_6 | P6_12
    tag                    text not null,       -- MUST_FIX | STRENGTHENS
    completion_criterion   text not null,
    assignee_user_id       uuid,
    due_date               date,
    status                 text not null default 'OPEN',  -- OPEN | IN_PROGRESS | DONE
    completion_note        text,
    completion_evidence_id uuid,                -- FR-DASH-2: evidence of completion
    created_at             timestamptz not null,
    updated_at             timestamptz not null,
    completed_at           timestamptz
);

create index action_item_journal_idx on action_item (journal_id, status);
create unique index action_item_journal_action_unique on action_item (journal_id, catalogue_action_id);

-- ---------------------------------------------------------------- audit_schedule (FR-DASH-3)
create table audit_schedule (
    id                     uuid primary key,
    org_id                 uuid not null,
    journal_id             uuid not null references journal (id),
    cadence                text not null,       -- MONTHLY | QUARTERLY | SEMIANNUAL | ANNUAL
    next_run_at            timestamptz not null,
    notify_email           boolean not null default true,
    active                 boolean not null default true,
    last_audit_id          uuid,
    last_notified_audit_id uuid,
    created_by             uuid not null,
    created_at             timestamptz not null
);

create unique index audit_schedule_journal_unique on audit_schedule (journal_id);

-- ---------------------------------------------------------------- app_setting (FR-ADM-1, global)
create table app_setting (
    key        text primary key,
    value      jsonb not null,
    updated_by uuid,
    updated_at timestamptz not null
);

-- ---------------------------------------------------------------- row-level security
alter table api_key          enable row level security;
alter table webhook          enable row level security;
alter table webhook_delivery enable row level security;
alter table action_item      enable row level security;
alter table audit_schedule   enable row level security;

create policy api_key_tenant on api_key
    for all
    using (org_id = jrap_current_org() or jrap_system_access())
    with check (org_id = jrap_current_org() or jrap_system_access());

create policy webhook_tenant on webhook
    for all
    using (org_id = jrap_current_org() or jrap_system_access())
    with check (org_id = jrap_current_org() or jrap_system_access());

create policy webhook_delivery_tenant on webhook_delivery
    for all
    using (org_id = jrap_current_org() or jrap_system_access())
    with check (org_id = jrap_current_org() or jrap_system_access());

create policy action_item_tenant on action_item
    for all
    using (org_id = jrap_current_org() or jrap_system_access())
    with check (org_id = jrap_current_org() or jrap_system_access());

create policy audit_schedule_tenant on audit_schedule
    for all
    using (org_id = jrap_current_org() or jrap_system_access())
    with check (org_id = jrap_current_org() or jrap_system_access());

-- ---------------------------------------------------------------- grants
grant select, insert, update on api_key, webhook, action_item, audit_schedule to jrap_app;
grant select, insert on webhook_delivery to jrap_app;
grant select, insert, update on app_setting to jrap_app;
-- journal transfer (FR-JRN-3): the admin re-homes registration-time rows
grant update on evidence_link to jrap_app;
