-- Daily usage per tenant, purpose and model, kept current by a trigger on model_calls (docs/decisions.md, Task 26).
-- Days are UTC. tenant_id has no foreign key: usage stays after a tenant is deleted, so past totals don't change.
-- A null tenant_id is a call made for no tenant (tests, the evaluation harness); it still counts toward the platform's spend.
create table public.usage_daily (
    tenant_id     uuid,
    day           date          not null,
    purpose       text          not null,
    model         text          not null,
    requests      int           not null,
    errors        int           not null,
    input_tokens  bigint        not null,
    output_tokens bigint        not null,
    cached_tokens bigint        not null,
    cost_usd      numeric(14,8) not null,
    constraint usage_daily_key unique nulls not distinct (tenant_id, day, purpose, model)
);

create index on usage_daily (day);

-- Runs in the transaction of the insert, so a call and its usage are written together or not at all.
-- A cost the provider didn't report counts as 0.
create function public.add_model_call_to_usage_daily() returns trigger
    language plpgsql as
$$
begin
    insert into usage_daily (tenant_id, day, purpose, model, requests, errors,
                             input_tokens, output_tokens, cached_tokens, cost_usd)
    values (new.tenant_id, (new.created_at at time zone 'UTC')::date, new.purpose, new.model, 1,
            case when new.status = 'ok' then 0 else 1 end,
            new.input_tokens, new.output_tokens, new.cached_tokens, coalesce(new.cost_usd, 0))
    on conflict on constraint usage_daily_key do update
        set requests      = usage_daily.requests + 1,
            errors        = usage_daily.errors + excluded.errors,
            input_tokens  = usage_daily.input_tokens + excluded.input_tokens,
            output_tokens = usage_daily.output_tokens + excluded.output_tokens,
            cached_tokens = usage_daily.cached_tokens + excluded.cached_tokens,
            cost_usd      = usage_daily.cost_usd + excluded.cost_usd;
    return null;
end
$$;

create trigger model_calls_usage_daily
    after insert on model_calls
    for each row execute function add_model_call_to_usage_daily();

-- the calls logged before this migration
insert into usage_daily (tenant_id, day, purpose, model, requests, errors,
                         input_tokens, output_tokens, cached_tokens, cost_usd)
select tenant_id, (created_at at time zone 'UTC')::date, purpose, model, count(*),
       count(*) filter (where status <> 'ok'),
       sum(input_tokens), sum(output_tokens), sum(cached_tokens), coalesce(sum(cost_usd), 0)
from model_calls
group by 1, 2, 3, 4;
