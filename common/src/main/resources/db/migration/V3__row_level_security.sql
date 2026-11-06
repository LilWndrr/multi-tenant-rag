
do $$
begin
  if not exists (select from pg_roles where rolname = '${app_user}') then
    execute format('create role %I login password %L', '${app_user}', '${app_password}');
  end if;
end
$$;


grant usage on schema public to ${app_user};
grant select, insert, update, delete on all tables in schema public to ${app_user};
grant usage, select on all sequences in schema public to ${app_user};


alter default privileges in schema public grant select, insert, update, delete on tables to ${app_user};
alter default privileges in schema public grant usage, select on sequences to ${app_user};


revoke insert, update, delete on flyway_schema_history from ${app_user};



alter table documents enable row level security;
alter table documents force row level security;
create policy tenant_isolation on documents
  using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table chunks enable row level security;
alter table chunks force row level security;
create policy tenant_isolation on chunks
  using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table chunk_embeddings enable row level security;
alter table chunk_embeddings force row level security;
create policy tenant_isolation on chunk_embeddings
  using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table ingestion_jobs enable row level security;
alter table ingestion_jobs force row level security;
create policy tenant_isolation on ingestion_jobs
  using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
