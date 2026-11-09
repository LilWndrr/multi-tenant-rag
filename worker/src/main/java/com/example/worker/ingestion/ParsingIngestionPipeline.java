package com.example.worker.ingestion;

import com.example.common.enums.DocumentErrorCode;
import com.example.common.profile.LanguageProfile;
import com.example.common.tenant.TenantTransactions;
import com.example.worker.chunking.ChunkRepository;
import com.example.worker.chunking.Chunker;
import com.example.worker.chunking.TextChunk;
import com.example.worker.document.DocumentParseException;
import com.example.worker.document.DocumentParser;
import com.example.worker.document.ParsedDocument;
import com.example.worker.embedding.ChunkEmbedder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;

// Downloads, parses, chunks and embeds, then replaces the document's chunks and vectors in one transaction.
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
    private final ChunkEmbedder chunkEmbedder;
    private final ChunkRepository chunkRepository;

    public ParsingIngestionPipeline(S3Client s3Client, DocumentParser documentParser, DocumentStatusRepository documents,
                                    TenantTransactions tenantTransactions, @Value("${rag.aws.s3.bucket}") String bucketName,
                                    TenantProfileRepository tenantProfileRepository, Chunker chunker, ChunkEmbedder chunkEmbedder, ChunkRepository chunkRepository) {
        this.s3Client = s3Client;
        this.documentParser = documentParser;
        this.documents = documents;
        this.tenantTransactions = tenantTransactions;
        this.bucketName = bucketName;
        this.tenantProfileRepository = tenantProfileRepository;
        this.chunker = chunker;
        this.chunkEmbedder = chunkEmbedder;
        this.chunkRepository = chunkRepository;
    }

    @Override
    public boolean process(UUID documentId, UUID tenantId, DocumentSource source) {
        ParsedDocument parsed;
        try (ResponseInputStream<GetObjectResponse> content =
                     s3Client.getObject(request -> request.bucket(bucketName).key(source.s3Key()))) {
            parsed = documentParser.parse(content, source.contentType());
        } catch (DocumentParseException e) {
            throw new IngestionException(DocumentErrorCode.parse_failed, e);
        } catch (SdkException | IOException | UncheckedIOException e) {
            throw new IngestionException(DocumentErrorCode.file_unavailable, e);
        }
        LanguageProfile profile = tenantProfileRepository.findFor(tenantId);
        List<TextChunk> chunks = chunker.chunk(parsed, profile.chunk());
        // outside any transaction: a database connection must not wait on HTTP calls
        List<float[]> vectors;
        try {
            vectors = chunkEmbedder.embed(chunks, profile.embeddingModel(), tenantId);
        } catch (RuntimeException e) {
            throw new IngestionException(DocumentErrorCode.embedding_failed, e);
        }

        boolean saved = tenantTransactions.run(tenantId, () -> {
            // locks the row, so a PUT can't switch the version while the chunks are replaced
            if (!documents.lockIfCurrent(documentId, source.s3Key())) {
                return false;
            }
            chunkRepository.deleteByDocument(documentId);
            for (int i = 0; i < chunks.size(); i++) {
                chunkRepository.insertChunkAndVectorRow(tenantId, documentId, chunks.get(i),
                        profile.textSearchConfig(), profile.embeddingModel(), vectors.get(i));
            }
            return true;
        });
        // counts only: document text never goes in the logs
        if (saved) {
            log.info("document {}: {} sections, {} chunks", documentId, parsed.sections().size(), chunks.size());
        } else {
            log.info("document {}: replaced by a newer version during processing, nothing saved", documentId);
        }
        return saved;
    }
}
