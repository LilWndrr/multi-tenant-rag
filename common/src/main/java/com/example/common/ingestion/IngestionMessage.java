package com.example.common.ingestion;

import java.util.UUID;

// The SQS body: the api sends it after the document row commits, the worker reads it.
// Ids only: the worker loads everything else from the row and the file from S3.
public record IngestionMessage(UUID documentId, UUID tenantId) {
}
