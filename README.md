# multi-tenant-rag

[![CI](https://github.com/LilWndrr/multi-tenant-rag/actions/workflows/ci.yml/badge.svg)](https://github.com/LilWndrr/multi-tenant-rag/actions/workflows/ci.yml)

A multi-tenant RAG platform: Java 21, Spring Boot 4, Postgres with pgvector, and OpenRouter behind a `ModelGateway` that logs the token counts and cost of every call.

## Modules

- `common`: model gateway, language profiles, shared code
- `api`: tenant admin, API keys, the public `/v1` API; owns the Flyway migrations
- `worker`: background jobs

## Run locally

Requirements: JDK 21 and Docker.

```bash
docker compose up -d
./mvnw verify
```

`./mvnw verify` uses Testcontainers, so Docker must be running. Tests that call OpenRouter run only when `OPENROUTER_API_KEY` is set.

Language-specific settings live in `common/src/main/resources/profiles/`; `scripts/check-no-language-names.sh` keeps them out of Java code.

Design choices are recorded in [docs/decisions.md](docs/decisions.md).
