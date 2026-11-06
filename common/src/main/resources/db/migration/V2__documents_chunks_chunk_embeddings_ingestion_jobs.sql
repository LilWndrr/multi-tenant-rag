

create table public.documents (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references tenants(id) on delete cascade,
  file_name text not null,
  content_type text not null,
  size_bytes bigint not null,
  checksum bytea not null,                       -- SHA-256 of the file
  s3_key text not null,
  status text not null check ( status in ('queued', 'processing', 'ready', 'failed') ),
  error text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (tenant_id, checksum)                   -- per tenant: a global unique would tell tenant B that tenant A has the file
);

create table public.chunks (
  id bigint primary key generated always as identity,
  tenant_id uuid not null references tenants(id) on delete cascade,
  document_id uuid not null references documents(id) on delete cascade,
  ordinal int not null,
  content text not null,
  heading_path text not null default '',
  token_count int not null,
  ts_config regconfig not null,                  -- from the tenant's language profile
  tsv tsvector generated always as (to_tsvector(ts_config, content)) stored,
  unique (document_id, ordinal)
);

create index on chunks using gin (tsv);

-- vector has no dimension: each model stores its own length; one partial HNSW index per model casts to it
create table public.chunk_embeddings (
  chunk_id bigint not null references chunks(id) on delete cascade,
  tenant_id uuid not null references tenants(id) on delete cascade,
  model text not null,
  embedding vector not null,
  primary key (chunk_id, model)
);

-- queries must use the same cast and model filter, or Postgres won't pick this index
create index chunk_embeddings_bge_m3_hnsw on chunk_embeddings
  using hnsw ((embedding::vector(1024)) vector_cosine_ops)
  where model = 'bge-m3';

-- one row per attempt
create table public.ingestion_jobs (
  id bigint primary key generated always as identity,
  tenant_id uuid not null references tenants(id) on delete cascade,
  document_id uuid not null references documents(id) on delete cascade,
  attempt int not null default 1,
  status text not null check ( status in ('running', 'succeeded', 'failed') ),
  error text,
  started_at timestamptz not null default now(),
  finished_at timestamptz
);

create index on ingestion_jobs (document_id);
