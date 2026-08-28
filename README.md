# JRAP — Journal Readiness Audit Platform

AI-assisted, evidence-based Scopus-readiness auditing of scholarly journals.
Implements the JRAP SRS v1.0 (27 Aug 2026). See the project doc
"Implementation Plan (Phased)" for the phase breakdown — payments (FR-BILL)
ship last; Phase 9 is a beta release without billing.

## Layout (SRS §4 module boundaries, NFR-MNT-1)

| Module | Responsibility |
|---|---|
| `jrap-common` | Shared kernel: tenant context, errors (RFC 9457), time, ids |
| `jrap-tenancy` | Organisations, users, roles, auth domain, security audit log |
| `jrap-crawl` | Politeness engine, OJS profile, snapshot store *(Phase 3)* |
| `jrap-extract` | Deterministic parsers, PDF/OCR, LLM extraction fallback *(Phase 4)* |
| `jrap-integrations` | OpenAlex / Crossref / DOAJ / ISSN adapters, polite HTTP, ApiRecord cache |
| `jrap-registry` | Journal registry + core Finding/Evidence domain (FR-JRN) |
| `jrap-analysis` | Deterministic gateway checks, CSAB scoring, red flags *(Phase 5)* |
| `jrap-review-reporting` | Finding queue, drafting, citation guard, exports *(Phases 6–7)* |
| `jrap-ai-gateway` | Single LLM entry point: providers, prompt registry, budgets *(Phase 4)* |
| `jrap-api` | REST controllers, Spring Security (JWT), OpenAPI |
| `jrap-app` | Boot assembly, configuration, Flyway migrations, integration tests |
| `frontend/` | React + TypeScript SPA, EN/AR (RTL) i18n |

## Run locally

```bash
docker compose up -d          # PostgreSQL 16, Redis 7, RabbitMQ, MinIO
mvn -pl jrap-app spring-boot:run
cd frontend && npm install && npm run dev
```

## Test

```bash
mvn verify                    # unit + integration (embedded PostgreSQL)
```

## Notices

JRAP is an independent product of HM Codes Research and Development. It is not
affiliated with, endorsed by, or connected to Elsevier B.V. or Scopus.
