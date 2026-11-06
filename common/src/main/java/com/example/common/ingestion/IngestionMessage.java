package com.example.common.ingestion;

import java.util.UUID;


public record IngestionMessage(UUID documentId, UUID tenantId) {
}
