package com.example.worker.ingestion;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.UUID;

// ingestion_jobs has row-level security: call these only inside tenantTransactions.run(tenantId, ...).
@Repository
public class IngestionRepository {

    private final JdbcClient jdbc;

    public IngestionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long start(UUID tenantId, UUID documentId, int attempt) {
        return jdbc.sql("""
                        insert into ingestion_jobs (tenant_id, document_id, attempt, status)
                        values (:tenantId, :documentId, :attempt, 'running')
                        returning id
                        """)
                .param("tenantId", tenantId)
                .param("documentId", documentId)
                .param("attempt", attempt)
                .query(Long.class)
                .single();
    }

    public void fail(long jobId, String error) {
        jdbc.sql("update ingestion_jobs set status = 'failed', error = :error, finished_at = now() where id = :id")
                .param("error", error)
                .param("id", jobId)
                .update();
    }

    public void succeed(long jobId) {
        jdbc.sql("update ingestion_jobs set status = 'succeeded', finished_at = now() where id = :id")
                .param("id", jobId)
                .update();
    }
}
