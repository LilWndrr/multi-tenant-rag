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

    public void markProcessing(UUID documentId) { setStatus(documentId, DocumentProcessStatus.processing, null); }
    public void markReady(UUID documentId)      { setStatus(documentId, DocumentProcessStatus.ready, null); }
    public void markFailed(UUID documentId, String error) { setStatus(documentId, DocumentProcessStatus.failed, error); }

    private void setStatus(UUID documentId, DocumentProcessStatus status, String error) {
        jdbc.sql("update documents set status = :status, error = :error, updated_at = now() where id = :id")
                .param("id", documentId)
                .param("status", status.name())
                .param("error", error)
                .update();
    }
}
