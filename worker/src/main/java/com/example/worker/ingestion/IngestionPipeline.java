package com.example.worker.ingestion;

import java.util.UUID;

public interface IngestionPipeline {

    // source is the version this job read when it started; its s3Key changes with every PUT.
    // Returns false when a newer version replaced it in the meantime: nothing is saved, the newer message does the work.
    // Throws if the document can't be processed; the caller records the failure and SQS retries.
    boolean process(UUID documentId, UUID tenantId, DocumentSource source);
}
