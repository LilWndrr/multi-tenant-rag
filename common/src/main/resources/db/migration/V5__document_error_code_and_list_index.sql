-- What clients see when a document fails. The raw message stays in error, for logs and debugging only:
-- it can hold provider or AWS details that aren't the client's business.
alter table documents add column error_code text
  check (error_code in ('queue_unavailable', 'file_unavailable', 'parse_failed', 'embedding_failed', 'internal_error'));

-- documents that failed before this migration: the API's queue message is the only one we can tell apart
update documents
set error_code = case when error = 'could not queue for ingestion' then 'queue_unavailable' else 'internal_error' end
where status = 'failed';

-- GET /v1/documents: newest first, paged by (created_at, id); row-level security adds the tenant_id filter
create index documents_tenant_created_at_id on documents (tenant_id, created_at desc, id desc);
