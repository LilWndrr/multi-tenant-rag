package com.example.worker;

import com.example.common.profile.LanguageProfiles;
import com.example.common.tenant.TenantTransactions;
import com.example.worker.ingestion.DocumentSource;
import com.example.worker.ingestion.IngestionPipeline;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// The real pipeline end to end: a file in S3, a message on the queue, the listener, the parser, the chunker
// and the database. Only the model provider is fake.
@SpringBootTest
@Import({TestcontainersConfiguration.class, FakeGatewayConfiguration.class})
class ParsingPipelineTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Autowired
    S3Client s3;
    @Autowired
    SqsClient sqs;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    TenantTransactions tenantTransactions;
    @Autowired
    LanguageProfiles profiles;
    @Autowired
    IngestionPipeline pipeline;
    @Autowired
    FakeModelGateway gateway;

    private record Ingested(UUID tenantId, UUID documentId, Map<String, Object> document) {}

    @AfterEach
    void resetGateway() {
        gateway.reset();
    }

    @Test
    void parseableFile_endsReady_withOneVectorPerChunk() throws IOException {
        Ingested ingested = ingest(sample("sample.docx"), DOCX, true, "en");

        assertThat(ingested.document().get("status")).isEqualTo("ready");
        assertThat(ingested.document().get("error")).isNull();
        assertThat(ingested.document().get("error_code")).isNull();
        assertThat(chunkCount(ingested)).isPositive();
        assertThat(vectorCount(ingested)).isEqualTo(chunkCount(ingested));
        assertThat(storedValues(ingested, "e.model")).containsOnly(profiles.get("en").embeddingModel());
        assertThat(storedValues(ingested, "c.heading_path")).contains("Kurulum", "Kurulum > Yapılandırma");
    }

    @Test
    void chunksUseTheSearchConfigOfTheTenantsProfile() throws IOException {
        Ingested first = ingest(sample("sample.md"), "text/markdown", true, "tr");
        Ingested second = ingest(sample("sample.md"), "text/markdown", true, "en");

        assertThat(profiles.get("tr").textSearchConfig()).isNotEqualTo(profiles.get("en").textSearchConfig());
        assertThat(storedValues(first, "c.ts_config")).containsOnly(profiles.get("tr").textSearchConfig());
        assertThat(storedValues(second, "c.ts_config")).containsOnly(profiles.get("en").textSearchConfig());
    }

    @Test
    void processingADocumentAgain_replacesItsChunks_withoutDuplicates() throws IOException {
        Ingested ingested = ingest(sample("sample.md"), "text/markdown", true, "en");
        int chunks = chunkCount(ingested);

        assertThat(pipeline.process(ingested.documentId(), ingested.tenantId(), source(ingested))).isTrue();

        assertThat(chunkCount(ingested)).isEqualTo(chunks);
        assertThat(vectorCount(ingested)).isEqualTo(chunks);
    }

    // A job that started before a PUT finishes after it: its old content must not replace the new version's chunks.
    @Test
    void outdatedVersion_savesNothing_andLeavesTheChunksAsTheyAre() throws IOException {
        Ingested ingested = ingest(sample("sample.md"), "text/markdown", true, "en");
        DocumentSource oldVersion = source(ingested);
        List<String> chunksBefore = storedValues(ingested, "c.content");

        String newKey = ingested.tenantId() + "/" + ingested.documentId() + "/" + UUID.randomUUID();
        s3.putObject(r -> r.bucket("rag-documents").key(newKey),
                RequestBody.fromString("# Yeni\n\nTamamen farklı bir içerik.", StandardCharsets.UTF_8));
        tenantTransactions.run(ingested.tenantId(), () -> jdbc.sql("update documents set s3_key = :key where id = :id")
                .param("key", newKey)
                .param("id", ingested.documentId())
                .update());

        assertThat(pipeline.process(ingested.documentId(), ingested.tenantId(), oldVersion)).isFalse();
        assertThat(storedValues(ingested, "c.content")).containsExactlyInAnyOrderElementsOf(chunksBefore);

        // the current version still goes through
        assertThat(pipeline.process(ingested.documentId(), ingested.tenantId(), source(ingested))).isTrue();
        assertThat(storedValues(ingested, "c.content")).singleElement().asString().contains("Tamamen farklı");
    }

    @Test
    void embeddingFailure_endsFailed_andSavesNoChunks() throws IOException {
        gateway.failWith(new IllegalStateException("provider unavailable"));

        Ingested ingested = ingest(sample("sample.md"), "text/markdown", true, "en");

        assertThat(ingested.document().get("status")).isEqualTo("failed");
        assertThat(ingested.document().get("error")).isEqualTo("provider unavailable");
        assertThat(ingested.document().get("error_code")).isEqualTo("embedding_failed");
        assertThat(chunkCount(ingested)).isZero();
        assertThat(gateway.embedCalls()).isEqualTo(3);   // one per attempt
    }

    @Test
    void hundredPagePdf_endsReady_withOneVectorPerChunk_inBatches() throws IOException {
        Ingested ingested = ingest(pdf(100), "application/pdf", true, "en");

        assertThat(ingested.document().get("status")).isEqualTo("ready");
        int chunks = chunkCount(ingested);
        assertThat(chunks).isGreaterThan(50);
        assertThat(vectorCount(ingested)).isEqualTo(chunks);
        assertThat(gateway.embedCalls()).isEqualTo((chunks + 1) / 2);   // batch size 2 in tests
    }

    @Test
    void corruptFile_endsFailed_withTheParserError() {
        byte[] corrupt = "%PDF-1.7 garbage".getBytes(StandardCharsets.UTF_8);

        Ingested ingested = ingest(corrupt, "application/pdf", true, "en");

        assertThat(ingested.document().get("status")).isEqualTo("failed");
        assertThat((String) ingested.document().get("error")).contains("could not parse application/pdf");
        assertThat(ingested.document().get("error_code")).isEqualTo("parse_failed");
        assertThat(gateway.embedCalls()).isZero();
    }

    @Test
    void fileMissingFromS3_endsFailed() {
        Ingested ingested = ingest(new byte[0], "application/pdf", false, "en");

        assertThat(ingested.document().get("status")).isEqualTo("failed");
        assertThat((String) ingested.document().get("error")).isNotBlank();
        assertThat(ingested.document().get("error_code")).isEqualTo("file_unavailable");
    }

    private byte[] sample(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/samples/" + name)) {
            return in.readAllBytes();
        }
    }

    private static byte[] pdf(int pages) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int page = 0; page < pages; page++) {
                PDPage pdfPage = new PDPage();
                document.addPage(pdfPage);
                try (PDPageContentStream content = new PDPageContentStream(document, pdfPage)) {
                    content.beginText();
                    content.setFont(font, 11);
                    content.setLeading(14);
                    content.newLineAtOffset(50, 720);
                    for (int line = 0; line < 45; line++) {
                        content.showText("Page " + page + " line " + line + " holds a short sentence of sample text for the test.");
                        content.newLine();
                    }
                    content.endText();
                }
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    private Ingested ingest(byte[] content, String contentType, boolean uploadToS3, String profile) {
        UUID tenantId = jdbc.sql("insert into tenants (name, language_profile) values (:name, :profile) returning id")
                .param("name", "t-" + UUID.randomUUID())
                .param("profile", profile)
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
        return new Ingested(tenantId, documentId, document(tenantId, documentId));
    }

    private Map<String, Object> document(UUID tenantId, UUID documentId) {
        return tenantTransactions.run(tenantId, () -> jdbc.sql("select status, error, error_code from documents where id = :id")
                .param("id", documentId)
                .query()
                .singleRow());
    }

    private DocumentSource source(Ingested ingested) {
        return tenantTransactions.run(ingested.tenantId(), () -> jdbc.sql("select s3_key, content_type from documents where id = :id")
                .param("id", ingested.documentId())
                .query((rs, n) -> new DocumentSource(rs.getString("s3_key"), rs.getString("content_type")))
                .single());
    }

    private int chunkCount(Ingested ingested) {
        return tenantTransactions.run(ingested.tenantId(), () -> jdbc.sql("select count(*) from chunks where document_id = :id")
                .param("id", ingested.documentId())
                .query(Integer.class)
                .single());
    }

    private int vectorCount(Ingested ingested) {
        return tenantTransactions.run(ingested.tenantId(), () -> jdbc.sql("""
                        select count(*) from chunk_embeddings e
                        join chunks c on c.id = e.chunk_id
                        where c.document_id = :id
                        """)
                .param("id", ingested.documentId())
                .query(Integer.class)
                .single());
    }

    private List<String> storedValues(Ingested ingested, String column) {
        return tenantTransactions.run(ingested.tenantId(), () -> jdbc.sql("""
                        select %s::text from chunks c
                        join chunk_embeddings e on e.chunk_id = c.id
                        where c.document_id = :id
                        """.formatted(column))
                .param("id", ingested.documentId())
                .query(String.class)
                .list());
    }
}
