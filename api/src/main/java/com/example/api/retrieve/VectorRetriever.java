package com.example.api.retrieve;

import com.example.common.model.ModelCall;
import com.example.common.model.ModelEmbedResponse;
import com.example.common.model.ModelGateway;
import com.example.common.profile.LanguageProfile;
import com.example.common.tenant.TenantTransactions;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

// Embeds the question with the profile's model (the same one the chunks were embedded with, or the vectors can't be
// compared) and asks Postgres for the nearest chunks. The question goes to the provider but is never logged.
@Component
public class VectorRetriever implements Retriever {

    private final ModelGateway modelGateway;
    private final TenantTransactions tenantTransactions;
    private final ChunkSearchRepository chunkSearchRepository;

    public VectorRetriever(ModelGateway modelGateway, TenantTransactions tenantTransactions,
                           ChunkSearchRepository chunkSearchRepository) {
        this.modelGateway = modelGateway;
        this.tenantTransactions = tenantTransactions;
        this.chunkSearchRepository = chunkSearchRepository;
    }

    @Override
    public List<RetrievedChunk> retrieve(UUID tenantId, LanguageProfile profile, String question, int limit) {
        ModelEmbedResponse response = modelGateway.embed(
                new ModelCall(tenantId, "query-embedding", profile.embeddingModel()), List.of(question));
        if (response.vectors().size() != 1) {
            throw new IllegalStateException("expected 1 vector, got " + response.vectors().size());
        }
        float[] vector = response.vectors().getFirst();
        if (vector.length != profile.embeddingDimensions()) {
            throw new IllegalStateException("expected " + profile.embeddingDimensions() + " dimensions, got " + vector.length);
        }
        return tenantTransactions.run(tenantId, () ->
                chunkSearchRepository.nearest(profile.embeddingModel(), profile.embeddingDimensions(), vector, limit));
    }
}
