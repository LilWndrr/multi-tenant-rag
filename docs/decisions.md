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

## Task 12: upload order, and what happens when SQS is down (2026-11-04)

- **What:** `POST /v1/documents` checks the file (empty → 400, over 20 MB → 413, type detected from the bytes by Tika, anything but PDF/DOCX/HTML/Markdown → 415), hashes it, returns the tenant's existing document if the checksum is known (200), otherwise puts it in S3 at `{tenantId}/{documentId}`, commits a `queued` row, and only then sends `{documentId, tenantId}` to SQS (202).
- **Why this order:** S3 and SQS can't join a database transaction. Sending before the commit would let the worker pick up a message for a row it can't see yet; writing S3 first means a committed row always has its file.
- **SQS failure:** the row is marked `failed` and the client gets 503 to retry. Chosen over a transactional outbox (row + outbox in one transaction, a poller sends) because it needs no extra table or background job; the cost is that a crash between commit and send leaves a `queued` row nobody will process. Revisit with an outbox or a sweeper for old `queued` rows if that shows up.
- **Duplicates:** the checksum is unique per tenant. Two identical uploads at the same time both pass the check; the loser hits the unique constraint, deletes its S3 copy and returns the winner's id.
- **Not yet:** a duplicate of a `failed` document returns the failed one, so re-uploading doesn't retry it (Task 17, idempotency).

## Task 13: ingestion worker on a plain SDK polling loop (2026-11-06)

- **What:** `IngestionListener` long-polls SQS on one thread, one message at a time. `IngestionProcessor` writes an `ingestion_jobs` row per attempt (the attempt number is SQS's `ApproximateReceiveCount`) and moves the document through `processing` to `ready`. On failure the message isn't deleted: its visibility timeout is shortened to `retry-delay-seconds x attempt`, and after `maxReceiveCount` (3) receives SQS moves it to `ingestion-dlq`. The third failure also marks the document `failed` with the error.
- **Why no `@SqsListener`:** Spring Cloud AWS's compatibility table lists Boot 4.0.x and we're on 4.1. The loop is about 50 lines, and the job and status logic would be the same either way. It's separate from `IngestionProcessor`, so it can be swapped if that support arrives.
- **One message at a time:** the visibility timer starts at receive, so messages waiting behind a slow one in a batch could time out and use up attempts without being tried. To be revisited with more threads if throughput needs it.
- **Migrations moved to `common`:** the worker's tests need the schema. Flyway is test-scoped in the worker, so in production only the api migrates.
- **Delivery is at least once:** a repeat for a `ready` document is skipped and deleted; real idempotency is Task 17.
- **`rag.ingestion.max-attempts` must match `maxReceiveCount`** in `docker/localstack/init.sh`, or the document's status and the DLQ disagree.
- **Not yet:** the visibility timeout is 300 s, and a document that takes longer gets redelivered while still being processed. Task 16 needs a heartbeat or a longer timeout. A worker crash during the last attempt leaves the document in `processing` with its message headed for the DLQ. `NoOpIngestionPipeline` marks every document `ready` with no chunks until Tasks 14-16.
- **Evidence:** `IngestionListenerTest`, against real LocalStack queues: an always-failing document gets 3 failed jobs (attempts 1, 2, 3), the document ends `failed` with the error and the message is in the DLQ; success, failure then success, an already-`ready` repeat, and an unreadable message (DLQ, and the listener keeps running).
