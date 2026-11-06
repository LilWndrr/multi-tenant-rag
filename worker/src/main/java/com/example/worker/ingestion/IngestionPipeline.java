package com.example.worker.ingestion;

import java.util.UUID;

public interface IngestionPipeline {

    // Throws if the document can't be processed; the caller records the failure and SQS retries.
    void process(UUID documentId, UUID tenantId);
}
