package com.example.worker.embedding;

import com.example.common.model.ModelCall;
import com.example.common.model.ModelEmbedResponse;
import com.example.common.model.ModelGateway;
import com.example.worker.chunking.TextChunk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class ChunkEmbedder {

    private final int batchSize;
    private final ModelGateway modelGateway;

    public ChunkEmbedder(@Value("${rag.embedding.batch-size}") int batchSize, ModelGateway modelGateway) {
        this.batchSize = batchSize;
        this.modelGateway = modelGateway;
    }

    // One vector per chunk, in the same order: the position is what links a vector to its chunk.
    public List<float[]> embed(List<TextChunk> chunks, String model, UUID tenantId) {
        ModelCall call = new ModelCall(tenantId, "ingestion-embedding", model);
        List<float[]> vectors = new ArrayList<>(chunks.size());

        for (int from = 0; from < chunks.size(); from += batchSize) {
            List<TextChunk> batch = chunks.subList(from, Math.min(from + batchSize, chunks.size()));
            List<String> texts = batch.stream().map(ChunkEmbedder::textFor).toList();

            ModelEmbedResponse response = modelGateway.embed(call, texts);
            if (response.vectors().size() != batch.size()) {
                throw new IllegalStateException("expected " + batch.size() + " vectors, got " + response.vectors().size());
            }
            vectors.addAll(response.vectors());
        }
        return vectors;
    }

    private static String textFor(TextChunk chunk) {
        return chunk.headingPath().isEmpty()
                ? chunk.content()
                : chunk.headingPath() + "\n\n" + chunk.content();
    }
}
