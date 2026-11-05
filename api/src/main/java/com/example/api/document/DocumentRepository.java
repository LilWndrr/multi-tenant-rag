package com.example.api.document;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

// documents has row-level security: call these only inside tenantTransactions.run(tenantId, ...).
@Repository
public class DocumentRepository {

    private final JdbcClient jdbcClient;

    public DocumentRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void create(UUID id, UUID tenantId, String fileName, String contentType, long sizeBytes,
                       byte[] checksum, String s3Key, DocumentProcessStatus status) {
        jdbcClient.sql("""
                        insert into documents (id, tenant_id, file_name, content_type, size_bytes, checksum, s3_key, status)
                        values (:id, :tenantId, :fileName, :contentType, :sizeBytes, :checksum, :s3Key, :status)
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("fileName", fileName)
                .param("contentType", contentType)
                .param("sizeBytes", sizeBytes)
                .param("checksum", checksum)
                .param("s3Key", s3Key)
                .param("status", status.name())
                .update();
    }

    // No tenant filter needed: the policy already limits this to the current tenant.
    public Optional<DocumentSaveResponse> findByChecksum(byte[] checksum) {
        return jdbcClient.sql("select id, status from documents where checksum = :checksum")
                .param("checksum", checksum)
                .query((rs, n) -> new DocumentSaveResponse(
                        rs.getObject("id", UUID.class),
                        DocumentProcessStatus.valueOf(rs.getString("status"))))
                .optional();
    }

    public void markFailed(UUID id, String error) {
        jdbcClient.sql("update documents set status = 'failed', error = :error, updated_at = now() where id = :id")
                .param("id", id)
                .param("error", error)
                .update();
    }
}
