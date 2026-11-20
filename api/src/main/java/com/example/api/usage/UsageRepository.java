package com.example.api.usage;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// usage_daily has no row-level security (like model_calls, it holds counts, no document text), so every query
// names its tenant
@Repository
public class UsageRepository {

    private final JdbcClient jdbc;

    public UsageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<UsageReport.Row> forTenant(UUID tenantId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        select day, purpose, model, requests, errors, input_tokens, output_tokens, cached_tokens, cost_usd
                        from usage_daily
                        where tenant_id = :tenant and day between :from and :to
                        order by day, purpose, model
                        """)
                .param("tenant", tenantId).param("from", from).param("to", to)
                .query(UsageReport.Row.class)
                .list();
    }
}
