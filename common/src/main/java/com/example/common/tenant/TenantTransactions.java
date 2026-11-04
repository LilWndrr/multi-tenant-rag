package com.example.common.tenant;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

// Every read or write on a tenant content table (documents, chunks, chunk_embeddings, ingestion_jobs) goes through here.
// The row-level security policies compare tenant_id with app.tenant_id; without it they return no rows and reject inserts.
@Component
public class TenantTransactions {

    private final TransactionTemplate transactions;
    private final JdbcClient jdbc;

    public TenantTransactions(TransactionTemplate transactions, JdbcClient jdbc) {
        this.transactions = transactions;
        this.jdbc = jdbc;
    }


    public <T> T run(UUID tenantId, Supplier<T> work) {
        Objects.requireNonNull(tenantId, "tenantId");
        return transactions.execute(status -> {
            // true = local to this transaction: cleared at commit, so a pooled connection never carries it to the next request
            jdbc.sql("select set_config('app.tenant_id', :tenantId, true)")
                    .param("tenantId", tenantId.toString())
                    .query(String.class)
                    .single();
            return work.get();
        });
    }

    public void run(UUID tenantId, Runnable work) {
        run(tenantId, () -> {
            work.run();
            return null;
        });
    }
}
