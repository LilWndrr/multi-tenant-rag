package com.example.api.document;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import com.example.common.enums.DocumentErrorCode;
import com.example.common.enums.DocumentProcessStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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

    public void markFailed(UUID id, DocumentErrorCode code, String error) {
        jdbcClient.sql("""
                        update documents set status = 'failed', error_code = :code, error = :error, updated_at = now()
                        where id = :id
                        """)
                .param("id", id)
                .param("code", code.name())
                .param("error", error)
                .update();
    }

    //
    public boolean requeueIfFailed(UUID id) {
        return jdbcClient.sql("""
                        update documents set status = 'queued', error = null, error_code = null, updated_at = now()
                        where id = :id and status = 'failed'
                        """)
                .param("id", id)
                .update() == 1;
    }

    public Optional<StoredDocument> findById(UUID id) {
        return jdbcClient.sql("select checksum, status, s3_key from documents where id = :id")
                .param("id", id)
                .query((rs, n) -> new StoredDocument(
                        rs.getBytes("checksum"),
                        DocumentProcessStatus.valueOf(rs.getString("status")),
                        rs.getString("s3_key")))
                .optional();
    }


    private static final String DETAILS_COLUMNS =
            "id, file_name, content_type, size_bytes, status, error_code, created_at, updated_at";

    public Optional<DocumentDetails> findDetails(UUID id) {
        return jdbcClient.sql("select " + DETAILS_COLUMNS + " from documents where id = :id")
                .param("id", id)
                .query(DocumentRepository::details)
                .optional();
    }

    // Newest first. after is null on the first page; otherwise the rows after that position in the same order.
    // The row comparison matches the sort and the V5 index (tenant_id, created_at desc, id desc).
    public List<DocumentDetails> findPage(DocumentCursor after, int limit) {
        if (after == null) {
            return jdbcClient.sql("select " + DETAILS_COLUMNS + " from documents order by created_at desc, id desc limit :limit")
                    .param("limit", limit)
                    .query(DocumentRepository::details)
                    .list();
        }
        return jdbcClient.sql("""
                        select %s from documents
                        where (created_at, id) < (:createdAt, :id)
                        order by created_at desc, id desc
                        limit :limit
                        """.formatted(DETAILS_COLUMNS))
                .param("createdAt", after.createdAt().atOffset(ZoneOffset.UTC))   // the driver takes OffsetDateTime, not Instant
                .param("id", after.id())
                .param("limit", limit)
                .query(DocumentRepository::details)
                .list();
    }

    private static DocumentDetails details(ResultSet rs, int rowNum) throws SQLException {
        String code = rs.getString("error_code");
        return new DocumentDetails(
                rs.getObject("id", UUID.class),
                rs.getString("file_name"),
                rs.getString("content_type"),
                rs.getLong("size_bytes"),
                DocumentProcessStatus.valueOf(rs.getString("status")),
                code != null ? DocumentErrorCode.valueOf(code) : null,
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    // Chunks, their vectors and the ingestion jobs go with it (on delete cascade). Returns the file's key, empty if not found.
    public Optional<String> delete(UUID id) {
        return jdbcClient.sql("delete from documents where id = :id returning s3_key")
                .param("id", id)
                .query(String.class)
                .optional();
    }

    public boolean replaceFile(UUID id, String oldS3Key, byte[] checksum, long sizeBytes, String contentType,
                               String fileName, String newS3Key) {
        return jdbcClient.sql("""
                        update documents
                        set checksum = :checksum, size_bytes = :sizeBytes, content_type = :contentType,
                            file_name = :fileName, s3_key = :newS3Key, status = 'queued', error = null, error_code = null,
                            updated_at = now()
                        where id = :id and s3_key = :oldS3Key
                        """)
                .param("id", id)
                .param("oldS3Key", oldS3Key)
                .param("checksum", checksum)
                .param("sizeBytes", sizeBytes)
                .param("contentType", contentType)
                .param("fileName", fileName)
                .param("newS3Key", newS3Key)
                .update() == 1;
    }
}
