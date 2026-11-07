package com.example.worker;

import com.example.common.tenant.TenantTransactions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// The real pipeline end to end: a file in S3, a message on the queue, the listener, the parser, the document's status.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ParsingPipelineTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    @Autowired
    S3Client s3;
    @Autowired
    SqsClient sqs;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    TenantTransactions tenantTransactions;

    @Test
    void parseableFileInS3_endsReady() throws IOException {
        byte[] sample;
        try (InputStream in = getClass().getResourceAsStream("/samples/sample.docx")) {
            sample = in.readAllBytes();
        }

        Map<String, Object> document = ingest(sample, "application/vnd.openxmlformats-officedocument.wordprocessingml.document", true);

        assertThat(document.get("status")).isEqualTo("ready");
        assertThat(document.get("error")).isNull();
    }

    @Test
    void corruptFile_endsFailed_withTheParserError() {
        byte[] corrupt = "%PDF-1.7 garbage".getBytes(StandardCharsets.UTF_8);

        Map<String, Object> document = ingest(corrupt, "application/pdf", true);

        assertThat(document.get("status")).isEqualTo("failed");
        assertThat((String) document.get("error")).contains("could not parse application/pdf");
    }

    @Test
    void fileMissingFromS3_endsFailed() {
        Map<String, Object> document = ingest(new byte[0], "application/pdf", false);

        assertThat(document.get("status")).isEqualTo("failed");
        assertThat((String) document.get("error")).isNotBlank();
    }

    private Map<String, Object> ingest(byte[] content, String contentType, boolean uploadToS3) {
        UUID tenantId = jdbc.sql("insert into tenants (name, language_profile) values (:name, 'en') returning id")
                .param("name", "t-" + UUID.randomUUID())
                .query(UUID.class)
                .single();
        UUID documentId = UUID.randomUUID();
        String s3Key = tenantId + "/" + documentId;

        if (uploadToS3) {
            s3.putObject(r -> r.bucket("rag-documents").key(s3Key), RequestBody.fromBytes(content));
        }
        tenantTransactions.run(tenantId, () -> jdbc.sql("""
                        insert into documents (id, tenant_id, file_name, content_type, size_bytes, checksum, s3_key, status)
                        values (:id, :tenantId, 'a', :contentType, :size, decode(md5(random()::text), 'hex'), :s3Key, 'queued')
                        """)
                .param("id", documentId)
                .param("tenantId", tenantId)
                .param("contentType", contentType)
                .param("size", content.length)
                .param("s3Key", s3Key)
                .update());

        String queueUrl = sqs.getQueueUrl(r -> r.queueName("ingestion")).queueUrl();
        sqs.sendMessage(r -> r.queueUrl(queueUrl)
                .messageBody("{\"documentId\":\"" + documentId + "\",\"tenantId\":\"" + tenantId + "\"}"));

        await().atMost(TIMEOUT).until(() -> {
            String status = document(tenantId, documentId).get("status").toString();
            return status.equals("ready") || status.equals("failed");
        });
        return document(tenantId, documentId);
    }

    private Map<String, Object> document(UUID tenantId, UUID documentId) {
        return tenantTransactions.run(tenantId, () -> jdbc.sql("select status, error from documents where id = :id")
                .param("id", documentId)
                .query()
                .singleRow());
    }
}
