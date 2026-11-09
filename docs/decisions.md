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

## Task 14: parsing with Tika, keeping the heading path (2026-11-06)

- **What:** `DocumentParser` turns a file into `ParsedDocument`, a list of sections with a heading path like `Kurulum > Docker`. PDF, DOCX and HTML go through Tika with our own SAX handler; Markdown has its own line parser, because Tika 4.2.0 has no Markdown module. Tika's parser modules are added one by one, not the full standard package.
- **Own handler instead of `BodyContentHandler`:** that one stops at 100,000 characters by default, which would silently cut long documents, and it flattens the headings. Ours caps at 20 million characters and fails, so a small compressed file can't expand until the worker runs out of memory.
- **Why not Docling or Unstructured:** Docling is the realistic alternative and can find headings in PDFs, but it's another heavy service to run and scale. Tika is a library inside the worker. Everything after the parser only sees `ParsedDocument`, so this can be swapped if Phase 4 shows missing PDF headings hurt retrieval. Documents already ingested would need re-parsing.
- **PDF headings:** PDFs mostly have no heading structure, so a PDF is one section with an empty path. This falls short of the task's "keeps the heading path" for this one format; accepted for now.
- **No text is a failure:** scanned PDFs extract nothing and there's no OCR in the plan, so they end `failed` with "no text extracted" rather than `ready` with no chunks.
- **Permanent failures still retry:** a corrupt file gets all 3 attempts before `failed`. Simpler than classifying errors, at the cost of a few wasted downloads.
- **Not yet:** the pipeline parses and discards the result; chunks arrive in Tasks 15-16, so a `ready` document still has no chunks. Markdown only recognises `#` headings, not underlined ones, and is always read as UTF-8.
- **Evidence:** `DocumentParserTest`, with one sample per format: heading paths and Turkish characters intact, code fences, a declared legacy charset in HTML, no 100,000-character cut-off, corrupt and textless PDFs throw. The DOCX and PDF samples are generated, not saved from Word or a real scanner. `ParsingPipelineTest` runs S3, queue, listener and parser end to end.

## Task 15: chunking by heading, size and overlap (2026-11-07)

- **What:** `HeadingAwareChunker` turns each parsed section into chunks using `chunk.size` and `chunk.overlap` from the tenant's language profile (400/50 tokens today). Chunks and overlap never cross a section, so every chunk has exactly one heading path. Ordinals run across the whole document.
- **How text is cut:** by line, then sentence, then word, and only as a last resort by character. Pieces are then packed in order until the next one would go over the limit.
- **The size limit wins over everything else:** a word longer than the limit is the one case cut mid-word. Overlap is a maximum: it only repeats whole pieces, and it's dropped when it leaves no room for the next piece or when the last piece is bigger than the overlap.
- **Token counts are approximate:** jtokkit with `cl100k_base` isn't the embedding model's own tokenizer, so counts are close rather than exact, especially for Turkish. 400 should be well below the model's input limit, so this affects chunk size rather than correctness. Task 16 should confirm the limit for each backend.
- **Sentence detection is simple:** `BreakIterator` with `Locale.ROOT` can break after abbreviations, which is harmless because packing usually rejoins them. It doesn't break before a lowercase word, so such text can fall back to word splitting: words stay whole, but a sentence can span two chunks.
- **Heading stays out of `content`:** it has its own column, and token counts cover `content` only. Whether to prepend it for embedding is a Task 16 decision.
- **`TenantProfileRepository` lives in the worker:** only the worker needs this lookup today. `common` holds what must be the same in both modules; this moves there if the API needs the same behaviour.
- **Not yet:** the pipeline chunks and discards the result, and only logs counts. Chunks and vectors are saved together in Task 16, so a `ready` document still has no chunks.
- **Evidence:** `HeadingAwareChunkerTest` re-counts every chunk with the real tokenizer and covers the limit, headings and ordinals across sections, whole sentences, overlap, long sentences, huge words, character safety, text that looks like a special token, and blank lines. `ParsingPipelineTest` runs the profile lookup and chunker through S3, the queue and the listener.

## Task 16: embeddings through OpenRouter only, no local models (2026-11-07)

- **What:** embeddings go through the gateway to OpenRouter, with the model taken from the tenant's language profile. This replaces the plan's second backend, `bge-m3` on a TEI container.
- **Why:** one provider to run; the same setup locally and on AWS, where a local model would need extra compute; and no model to host. If free models fail too often, the fallback is a paid model on OpenRouter, which is a profile change, an index migration and a re-embed.
- **Cost of this choice:** ingestion depends on OpenRouter and the model behind it, and document text goes to a third party. Check the chosen model's data policy before using anything beyond open corpora.
- **Reranker:** Task 23 also moves off TEI. It needs a hosted reranker, on OpenRouter if a suitable one is available; not checked yet.
- **Left in place:** the `bge-m3` index from `V2` stays, because migrations that have already run shouldn't be edited. It's unused and can be dropped by a later migration.
- **Model:** `nvidia/nemotron-3-embed-1b:free`, measured at 2048 dimensions. Nemotron 3.5 Embed 1B was the first choice, but OpenRouter showed about 1% daily uptime for it on 2026-11-07, against 100% for this one. The listing says 3.5 shares its text embedding space with this model; compare real vectors before relying on that to skip a re-embed.
- **`halfvec` index:** pgvector refuses an HNSW index on `vector` above 2000 dimensions, so `V4` casts to `halfvec(2048)`. The column doesn't change. Queries must use the same cast and model filter, or Postgres won't use the index. Models of 2000 dimensions or fewer use a plain `vector` index.
- **Embedded text:** heading path plus content, so a chunk like "Run the start command" still matches searches about its section. Stored `content` and full-text search use the content alone.
- **Saving:** embed first with no transaction open, so a connection never waits on HTTP. Then one transaction deletes the document's chunks and inserts the new chunks and vectors, so a failed embedding saves nothing and a retry leaves no duplicates.
- **Heartbeat (closes the gap from Task 13):** the listener sets the visibility timeout at receive and extends it every 60 s while a job runs, so a job over 300 s doesn't reach a second worker. It stops before the delete or the retry delay, so an extension can't overwrite that delay. `init.sh` still sets 300 s on the queue, which only applies to other consumers.
- **Removed:** `rag.models.embedding` from both main property files, since ingestion reads the model from the profile. `ModelGatewayLiveTest` keeps its own copy in the test properties.
- **Known limits:**
  - The gateway's retries last about 7 seconds, so a longer outage uses up the document's 3 attempts and it ends `failed`.
  - A crash during the last attempt still leaves a document in `processing`.
  - We do not check chunk length against the model; chunks of about 400 tokens are far below its listed 32,768 context length.
  - The batch size of 32 is our choice, as OpenRouter lists no limit for this model.
- **Evidence:**
  - Real provider on 2026-11-07, through S3, the queue, the listener and the database: a generated 100-page PDF reached `ready` in 10 s with 200 chunks and 200 vectors in 7 batches, all logged `ok` in `model_calls`, about 700 ms per batch. A Markdown document on the second profile reached `ready` with that profile's search config. This used generated text, on a free endpoint that may not always be this fast; real documents still need trying (Task 19).
  - `IngestionListenerTest` plays a second worker polling past the timeout; with the heartbeat effectively off, that test fails.
  - `ChunkRepositoryTest`, `ChunkEmbedderTest` and `ParsingPipelineTest` cover row-level security, wrong vector sizes, batching, order, replacing chunks, embedding failures and 100 pages. CI uses a fake provider and never calls OpenRouter.

## Task 17: repeat uploads, replacing a file, and delete (2026-11-09)

- **Repeat upload:** the checksum is looked up before any S3 write or queue message. A `queued`, `processing` or `ready` document comes back as it is with 200 and no message, so the worker never runs and nothing is embedded. A `failed` document is set back to `queued` and sent again with 202; the update only changes the row while it is still `failed`, so of two retries at once only one sends a message.
- **Replacing a file: `PUT /v1/documents/{id}`**, not matching by file name, since two different files can share a name. The id stays the same and the old chunks stay searchable until the new ones are saved. Same content as now is handled like a repeat upload; content that another document of the tenant already has is 409.
- **One S3 key per version** (`tenantId/documentId/<random UUID>`): the new file is written first and the old one is deleted only after the row points at the new one, so a failed update never leaves the row pointing at the wrong content. The row is updated only while it still has the old key, so a second PUT or a DELETE at the same time gets 409 and its new object is removed.
- **The key is the version the worker checks.** It reads the key at the start of each attempt. Before replacing chunks it locks the row and checks the key is unchanged; `markReady` and `markFailed` also only change that version. A job for a replaced version saves nothing and sets no status, and the newer message does the work. The key was chosen over hashing the downloaded bytes because it already changes exactly when the content does.
- **Delete:** the row goes first, in one statement, and takes the chunks, vectors and jobs with it through `on delete cascade`. Then the S3 object goes. If that fails, the client still gets 204 and the object is left behind with a warning in the log; a row pointing at a missing file would be worse. Another tenant's document is 404, through row-level security.
- **Known limits:**
  - A file replaced while its old job is still running can be embedded twice; only one result is kept.
  - Leftover S3 objects (a failed delete, or a crash between steps) are not cleaned up by anything yet.
  - Every replace embeds the whole document again. Reusing the vectors of unchanged chunks is a later task.
- **Evidence:** `DocumentTest` covers repeat uploads, retrying a failed document, the PUT cases (new content, same content, another document's content, unknown and other-tenant ids) and delete. `ParsingPipelineTest` and `IngestionListenerTest` cover the version check: an outdated job saves no chunks and marks the document neither `ready` nor `failed`.
