package com.example.worker.ingestion;

import com.example.common.enums.DocumentErrorCode;
import com.example.common.enums.DocumentProcessStatus;
import com.example.common.ingestion.IngestionMessage;
import com.example.common.tenant.TenantTransactions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class IngestionProcessor {

    private final IngestionRepository ingestionRepository;
    private final DocumentStatusRepository documentStatusRepository;
    private final IngestionPipeline ingestionPipeline;
    private final TenantTransactions tenantTransactions;
    private final int maxAttempts;

    public IngestionProcessor(IngestionRepository ingestionRepository, DocumentStatusRepository documentStatusRepository,
                              IngestionPipeline ingestionPipeline, TenantTransactions tenantTransactions,
                              @Value("${rag.ingestion.max-attempts}") int maxAttempts) {
        this.ingestionRepository = ingestionRepository;
        this.documentStatusRepository = documentStatusRepository;
        this.ingestionPipeline = ingestionPipeline;
        this.tenantTransactions = tenantTransactions;
        this.maxAttempts = maxAttempts;
    }


    public void process(IngestionMessage body, int attempt) {
        UUID tenantId = body.tenantId();
        UUID documentId = body.documentId();

        var status = tenantTransactions.run(tenantId, () -> documentStatusRepository.findStatus(documentId));
        if (status.isEmpty() || status.get() == DocumentProcessStatus.ready) {
            return;
        }

        // the version (s3Key) is read on every attempt, so a retry after a PUT works on the new file
        record Started(long jobId, DocumentSource source) {}
        Started started = tenantTransactions.run(tenantId, () -> {
            DocumentSource current = documentStatusRepository.findSource(documentId).orElseThrow();
            documentStatusRepository.markProcessing(documentId);
            return new Started(ingestionRepository.start(tenantId, documentId, attempt), current);
        });
        long jobId = started.jobId();
        String version = started.source().s3Key();

        boolean saved;
        try {
            saved = ingestionPipeline.process(documentId, tenantId, started.source());
        } catch (Exception e) {
            String error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            DocumentErrorCode code = e instanceof IngestionException tagged ? tagged.code() : DocumentErrorCode.internal_error;
            tenantTransactions.run(tenantId, () -> {
                ingestionRepository.fail(jobId, error);
                if (attempt >= maxAttempts) {
                    documentStatusRepository.markFailed(documentId, version, code, error);
                }
            });
            throw e;
        }

        tenantTransactions.run(tenantId, () -> {
            ingestionRepository.succeed(jobId);
            if (saved) {
                documentStatusRepository.markReady(documentId, version);
            }
            // not saved: a newer version replaced this one, and its own message sets the status
        });
    }
}
