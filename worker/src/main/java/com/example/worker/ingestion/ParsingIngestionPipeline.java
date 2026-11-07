package com.example.worker.ingestion;

import com.example.common.tenant.TenantTransactions;
import com.example.worker.chunking.Chunker;
import com.example.worker.chunking.TextChunk;
import com.example.worker.document.DocumentParser;
import com.example.worker.document.ParsedDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;

// Downloads, parses and chunks, so a corrupt or scanned file fails with a real error. Saving chunks and embedding come in Task 16.
@Component
public class ParsingIngestionPipeline implements IngestionPipeline {

    private static final Logger log = LoggerFactory.getLogger(ParsingIngestionPipeline.class);

    private final S3Client s3Client;
    private final DocumentParser documentParser;
    private final DocumentStatusRepository documents;
    private final TenantTransactions tenantTransactions;
    private final String bucketName;
    private final TenantProfileRepository tenantProfileRepository;
    private final Chunker chunker;

    public ParsingIngestionPipeline(S3Client s3Client, DocumentParser documentParser, DocumentStatusRepository documents,
                                    TenantTransactions tenantTransactions, @Value("${rag.aws.s3.bucket}") String bucketName,
                                    TenantProfileRepository tenantProfileRepository, Chunker chunker) {
        this.s3Client = s3Client;
        this.documentParser = documentParser;
        this.documents = documents;
        this.tenantTransactions = tenantTransactions;
        this.bucketName = bucketName;
        this.tenantProfileRepository = tenantProfileRepository;
        this.chunker = chunker;
    }

    @Override
    public void process(UUID documentId, UUID tenantId) {
        DocumentSource source = tenantTransactions.run(tenantId, () -> documents.findSource(documentId))
                .orElseThrow(() -> new IllegalStateException("document not found: " + documentId));

        ParsedDocument parsed;
        try (ResponseInputStream<GetObjectResponse> content =
                     s3Client.getObject(request -> request.bucket(bucketName).key(source.s3Key()))) {
            parsed = documentParser.parse(content, source.contentType());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        List<TextChunk> chunks = chunker.chunk(parsed, tenantProfileRepository.findFor(tenantId).chunk());

        // counts only: document text never goes in the logs
        log.info("document {}: {} sections, {} chunks", documentId, parsed.sections().size(), chunks.size());
    }
}
