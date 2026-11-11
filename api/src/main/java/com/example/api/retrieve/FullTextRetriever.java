package com.example.api.retrieve;

import com.example.common.profile.LanguageProfile;
import com.example.common.tenant.TenantTransactions;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

// Postgres full-text search with the profile's text-search config: finds exact words (an error code, a flag,
// a year) that embeddings blur. No model call.
@Component("full-text-retriever")
public class FullTextRetriever implements Retriever {

    private final TenantTransactions tenantTransactions;
    private final ChunkSearchRepository chunkSearchRepository;

    public FullTextRetriever(TenantTransactions tenantTransactions, ChunkSearchRepository chunkSearchRepository) {
        this.tenantTransactions = tenantTransactions;
        this.chunkSearchRepository = chunkSearchRepository;
    }

    @Override
    public List<RetrievedChunk> retrieve(UUID tenantId, LanguageProfile profile, String question, int limit) {
        return tenantTransactions.run(tenantId, () ->
                chunkSearchRepository.matching(profile.textSearchConfig(), question, limit));
    }
}
