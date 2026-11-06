CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE public.tenants (
                                   id uuid  primary key default gen_random_uuid(),
                                   name text  NOT NULL unique ,
                                   language_profile  text  not null ,
                                    settings jsonb not null default '{}'::jsonb,
                                    created_at timestamptz  NOT NULL DEFAULT now()
);

create table public.api_keys (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id) on delete cascade ,
    prefix text not null ,
    key_hash bytea not null unique,
    name text not null,
    created_at timestamptz  not null default now(),
    last_used_at timestamptz ,
    revoked_at timestamptz
);
CREATE INDEX ON api_keys (tenant_id);


create table public.model_calls (
  id bigint primary key generated always as identity,
  tenant_id uuid references tenants(id) ON DELETE SET NULl,
  purpose text not null ,
  provider text not null,
  model text not null,
  status text not null,
  input_tokens int not null,
  output_tokens int not null,
  cached_tokens int not null default 0,
  cost_usd numeric(12,8),
  latency_ms int not null,
  created_at timestamptz  not null default now()
);

CREATE INDEX ON model_calls (tenant_id,created_at);





