package com.example.api.retrieve;

import com.example.common.profile.LanguageProfile;

import java.util.List;
import java.util.UUID;


public interface Reranker {

    List<RetrievedChunk> rerank(UUID tenantId, LanguageProfile profile, String question,
                                List<RetrievedChunk> candidates, int limit);
}
