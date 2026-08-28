-- JRAP V1: tenancy, identity and access control (SRS FR-AUTH-1..3, FR-AUTH-5)
--
-- Tenant isolation (FR-AUTH-3) is enforced HERE, in the database, with row-level
-- security. The application sets two transaction-local settings at transaction begin
-- (see TenantAwareJpaTransactionManager):
--   app.current_org    — the caller's organisation id ('' when unauthenticated)
--   app.system_access  — 'on' only for pre-authentication flows and platform admin
-- The application connects as the restricted role jrap_app, which is subject to
-- these policies. Migrations run as the schema owner.

create function jrap_current_org() returns uuid
language sql stable as
$$ select nullif(current_setting('app.current_org', true), '')::uuid $$;

create function jrap_system_access() returns boolean
language sql stable as
$$ select coalesce(current_setting('app.system_access', true), 'off') = 'on' $$;

-- ---------------------------------------------------------------- organisation
create table organisation (
    id          uuid primary key,
    name        text not null,
    status      text not null,
    created_at  timestamptz not null
);

-- ---------------------------------------------------------------- app_user
create table app_user (
    id                 uuid primary key,
    org_id             uuid not null references organisation (id),
    email              text not null,
    password_hash      text,
    display_name       text not null,
    role               text not null,
    status             text not null,
    totp_secret        text,
    totp_enabled       boolean not null default false,
    created_at         timestamptz not null,
    email_verified_at  timestamptz
);

create unique index app_user_email_unique on app_user (lower(email));
create index app_user_org_idx on app_user (org_id);

-- ---------------------------------------------------------------- verification_token
create table verification_token (
    id          uuid primary key,
    user_id     uuid not null references app_user (id),
    org_id      uuid not null,
    token_hash  text not null unique,
    purpose     text not null,
    expires_at  timestamptz not null,
    used_at     timestamptz,
    created_at  timestamptz not null
);

-- ---------------------------------------------------------------- refresh_token
create table refresh_token (
    id          uuid primary key,
    user_id     uuid not null references app_user (id),
    org_id      uuid not null,
    token_hash  text not null unique,
    issued_at   timestamptz not null,
    expires_at  timestamptz not null,
    revoked_at  timestamptz,
    replaced_by uuid
);

create index refresh_token_user_idx on refresh_token (user_id);

-- ---------------------------------------------------------------- security_audit_log
-- Immutable, write-once (FR-AUTH-5): UPDATE and DELETE are rejected by trigger for
-- every role including the owner. Retention >= 2 years: no purge exists.
create table security_audit_log (
    id            bigint generated always as identity primary key,
    occurred_at   timestamptz not null,
    org_id        uuid,
    actor_user_id uuid,
    actor_email   text,
    event_type    text not null,
    details       jsonb not null default '{}'::jsonb,
    source_ip     text
);

create index security_audit_log_org_idx on security_audit_log (org_id, occurred_at desc);

create function jrap_forbid_mutation() returns trigger
language plpgsql as
$$
begin
    raise exception 'security_audit_log is immutable (FR-AUTH-5)';
end;
$$;

create trigger security_audit_log_immutable
    before update or delete on security_audit_log
    for each row execute function jrap_forbid_mutation();

-- ---------------------------------------------------------------- row-level security
alter table organisation       enable row level security;
alter table app_user           enable row level security;
alter table verification_token enable row level security;
alter table refresh_token      enable row level security;
alter table security_audit_log enable row level security;

create policy organisation_tenant on organisation
    for all
    using (id = jrap_current_org() or jrap_system_access())
    with check (id = jrap_current_org() or jrap_system_access());

create policy app_user_tenant on app_user
    for all
    using (org_id = jrap_current_org() or jrap_system_access())
    with check (org_id = jrap_current_org() or jrap_system_access());

create policy verification_token_tenant on verification_token
    for all
    using (org_id = jrap_current_org() or jrap_system_access())
    with check (org_id = jrap_current_org() or jrap_system_access());

create policy refresh_token_tenant on refresh_token
    for all
    using (org_id = jrap_current_org() or jrap_system_access())
    with check (org_id = jrap_current_org() or jrap_system_access());

-- Anyone may append to the audit log; reading is tenant- or system-scoped.
-- No UPDATE/DELETE policy exists, so both are denied by RLS as well as by trigger.
create policy security_audit_log_insert on security_audit_log
    for insert with check (true);

create policy security_audit_log_read on security_audit_log
    for select
    using (org_id = jrap_current_org() or jrap_system_access());

-- ---------------------------------------------------------------- application role
-- Restricted runtime role. Dev default password — production MUST rotate it:
--   alter role jrap_app with password '...';
do
$$
begin
    if not exists (select from pg_roles where rolname = 'jrap_app') then
        create role jrap_app login password 'jrap_app';
    end if;
end;
$$;

grant usage on schema public to jrap_app;
grant select, insert, update on organisation, app_user, verification_token, refresh_token to jrap_app;
grant delete on verification_token, refresh_token to jrap_app;
grant select, insert on security_audit_log to jrap_app;
grant usage, select on all sequences in schema public to jrap_app;
grant execute on function jrap_current_org(), jrap_system_access() to jrap_app;
