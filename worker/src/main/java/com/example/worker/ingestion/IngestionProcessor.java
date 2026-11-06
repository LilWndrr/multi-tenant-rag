package com.example.worker.ingestion;

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

        long jobId = tenantTransactions.run(tenantId, () -> {
            documentStatusRepository.markProcessing(documentId);
            return ingestionRepository.start(tenantId, documentId, attempt);
        });

        try {
            ingestionPipeline.process(documentId, tenantId);
        } catch (Exception e) {
            String error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            tenantTransactions.run(tenantId, () -> {
                ingestionRepository.fail(jobId, error);
                if (attempt >= maxAttempts) {
                    documentStatusRepository.markFailed(documentId, error);
                }
            });
            throw e;
        }

        tenantTransactions.run(tenantId, () -> {
            ingestionRepository.succeed(jobId);
            documentStatusRepository.markReady(documentId);
        });
    }
}
