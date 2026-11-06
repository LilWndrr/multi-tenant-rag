package com.example.worker.ingestion;

import org.springframework.stereotype.Component;

import java.util.UUID;

// Placeholder until Tasks 14-16 add parsing, chunking and embedding.
@Component
public class NoOpIngestionPipeline implements IngestionPipeline {

    @Override
    public void process(UUID documentId, UUID tenantId) {
    }
}
