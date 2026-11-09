package com.example.worker.ingestion;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import com.example.common.enums.DocumentProcessStatus;

import java.util.Optional;
import java.util.UUID;

@Repository
public class DocumentStatusRepository {

    private final JdbcClient jdbc;

    public DocumentStatusRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<DocumentProcessStatus> findStatus(UUID documentId) {
        return jdbc.sql("select status from documents where id = :id")
                .param("id", documentId)
                .query((rs, n) -> DocumentProcessStatus.valueOf(rs.getString("status")))
                .optional();
    }

    public Optional<DocumentSource> findSource(UUID documentId) {
        return jdbc.sql("select s3_key, content_type from documents where id = :id")
                .param("id", documentId)
                .query((rs, n) -> new DocumentSource(rs.getString("s3_key"), rs.getString("content_type")))
                .optional();
    }

    // true when the document still has this version (s3Key); the row stays locked until the transaction ends
    public boolean lockIfCurrent(UUID documentId, String s3Key) {
        return jdbc.sql("select 1 from documents where id = :id and s3_key = :s3Key for update")
                .param("id", documentId)
                .param("s3Key", s3Key)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    public void markProcessing(UUID documentId) {
        jdbc.sql("update documents set status = 'processing', error = null, updated_at = now() where id = :id")
                .param("id", documentId)
                .update();
    }

    // Both only change the version this job processed: an outdated job must not set the status of a newer one.
    public void markReady(UUID documentId, String s3Key)               { setFinalStatus(documentId, s3Key, DocumentProcessStatus.ready, null); }
    public void markFailed(UUID documentId, String s3Key, String error) { setFinalStatus(documentId, s3Key, DocumentProcessStatus.failed, error); }

    private void setFinalStatus(UUID documentId, String s3Key, DocumentProcessStatus status, String error) {
        jdbc.sql("""
                        update documents set status = :status, error = :error, updated_at = now()
                        where id = :id and s3_key = :s3Key
                        """)
                .param("id", documentId)
                .param("s3Key", s3Key)
                .param("status", status.name())
                .param("error", error)
                .update();
    }
}
