# Decisions

Short notes on choices the plan asks us to record: what, why, and the evidence.

## Task 7: model call cost comes from OpenRouter's `usage.cost` (2026-11-02)

- **What:** `model_calls.cost_usd` is read from the `cost` field OpenRouter adds to the response's `usage` block. Spring AI 2.0 runs on the OpenAI Java SDK, which keeps unknown fields in `CompletionUsage._additionalProperties()`, so no second HTTP call is needed.
- **Why:** the plan says cost is measured, not estimated; a price list would drift from what OpenRouter actually bills.
- **Evidence:** checked in the debugger on a live call: `additionalProperties` held `cost`, `cost_details` and `is_byok`.
- **Rules:** a missing or non-numeric `cost` is stored as null, never 0. We don't use BYOK; if we ever do, `cost` only covers OpenRouter's fee and the model cost moves to `cost_details.upstream_inference_cost`.

## Task 7: one retry layer for model calls (2026-11-03)

- **What:** Resilience4j (`resilience4j-retry` 2.4.0, core only) is the only retry layer: 4 attempts, 1 s / 2 s / 4 s backoff, retrying only 429, 5xx and network errors; 400/401/403/404 fail at once. The OpenAI SDK's and Spring AI's own retries are off (`spring.ai.openai.max-retries=0`, `spring.ai.retry.max-attempts=1`).
- **Why:** with all three layers on, one call could be tried up to 12 times, and the SDK's attempts never reach the gateway. With one layer, every attempt is a `model_calls` row, so failure rates and retry cost stay visible.
- **Streams:** retried only before the first token reaches the caller; after that a retry would show the start of the answer twice, so the real error is thrown instead.
- **Evidence:** live 502s from the free NVIDIA embedding endpoint were retried with backoff, and the call succeeded on the second attempt (one `error` row + one `ok` row).

## Task 8: language profiles are YAML files on the classpath (2026-11-03)

- **What:** `common/src/main/resources/profiles/<id>.yaml` holds the embedding model, reranker, chunk size/overlap, Postgres text-search config and prompts; the id is the file name. `LanguageProfiles` loads them all at startup with Jackson YAML, rejects unknown keys and invalid chunk settings, and tenant creation accepts any id that has a file.
- **Why:** the plan forbids language names in Java; in `common`'s classpath both api and worker read the same files. Adding a language is adding a file, and `scripts/check-no-language-names.sh` fails the build if `turkish`/`english` shows up in Java.
- **Chunks:** 400 tokens with 50 overlap for both languages, the plan's starting point; to be revisited once retrieval is benchmarked.
- **Not yet:** the embedding model is still also read from `rag.models.embedding`; ingestion (Task 16) should take it from the tenant's profile.

## Task 11: tenant isolation in Postgres with row-level security (2026-11-04)

- **What:** `documents`, `chunks`, `chunk_embeddings` and `ingestion_jobs` have RLS enabled and forced, with a policy comparing `tenant_id` to `app.tenant_id`. The app sets it per transaction through `TenantTransactions.run(tenantId, ...)` (`set_config(..., true)`, so it's cleared at commit and never travels with a pooled connection). An unset tenant matches no rows (`nullif(..., '')` keeps `''::uuid` from throwing).
- **Roles:** the app logs in as `rag_app` (no superuser, no `BYPASSRLS`, DML only); Flyway runs as the owner and creates `rag_app` in V3 from placeholders. Superusers ignore RLS even when it's forced, so logging in as `rag` would have made the policies decorative. Chosen over `SET ROLE` per transaction, which a superuser connection can undo.
- **Not covered:** `tenants`, `api_keys` and `model_calls`: the auth filter looks keys up before the tenant is known, admin endpoints span tenants, and some model calls have no tenant. Code on those tables still filters by `tenant_id` itself.
- **Evidence:** `RowLevelSecurityTest`: with tenant A's key, `select count(*) from documents` (no where clause) returns 0 while B has rows; no tenant set reads nothing; cross-tenant insert, update and delete are refused; tests run as `rag_app` and assert it can't bypass RLS.
