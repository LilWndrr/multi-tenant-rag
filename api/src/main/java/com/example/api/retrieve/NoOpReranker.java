package com.example.api.retrieve;

import com.example.common.profile.LanguageProfile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component("no-op-reranker")
public class NoOpReranker implements Reranker {

    @Override
    public List<RetrievedChunk> rerank(UUID tenantId, LanguageProfile profile, String question,
                                       List<RetrievedChunk> candidates, int limit) {
        return candidates.stream().limit(limit).toList();
    }
}
