-- Changes whenever a tenant's chunks change: the answer cache puts it in every key, so a change makes the tenant's
-- cached answers unreachable at once (docs/decisions.md, Task 27). Retrieval reads chunks directly, so chunks are what
-- answers depend on: a document being ingested, replaced or deleted all insert or delete chunks.
alter table tenants add column docs_version bigint not null default 0;

-- Set to the id of the transaction that changed the chunks: different for every such transaction, the same within one,
-- so ingesting 200 chunks updates the tenant row once, not 200 times. It runs in that transaction, so a rolled-back
-- ingestion changes nothing, and a committed one can't leave the old version behind.
create function public.bump_tenant_docs_version() returns trigger
    language plpgsql as
$$
declare
    changed uuid := case when tg_op = 'DELETE' then old.tenant_id else new.tenant_id end;
    xact    bigint := pg_current_xact_id()::text::bigint;
begin
    update tenants set docs_version = xact where id = changed and docs_version <> xact;
    return null;
end
$$;

create trigger chunks_bump_tenant_docs_version
    after insert or update or delete on chunks
    for each row execute function bump_tenant_docs_version();
