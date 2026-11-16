package com.example.api.retrieve;

import com.example.common.profile.LanguageProfile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

// Vector and full-text search together: each finds what the other misses (meaning versus exact terms, see
// docs/decisions.md, Task 21). Their lists are merged by rank (reciprocal rank fusion). Before merging, a relevance
// gate: if neither list's best score reaches the profile's minimum, the question gets no sources, so the answer step
// says it doesn't know instead of answering from unrelated text. The gate only catches clearly unrelated questions;
// near misses are left to the answer step, which answers only from its sources.
@Component("hybrid-retriever")
public class HybridRetriever implements Retriever {


    static final int CANDIDATES = 20;

    private final VectorRetriever vectorRetriever;
    private final FullTextRetriever fullTextRetriever;

    public HybridRetriever(VectorRetriever vectorRetriever, FullTextRetriever fullTextRetriever) {
        this.vectorRetriever = vectorRetriever;
        this.fullTextRetriever = fullTextRetriever;
    }


    @Override
    public List<RetrievedChunk> retrieve(UUID tenantId, LanguageProfile profile, String question, int limit) {
        List<RetrievedChunk> byMeaning = vectorRetriever.retrieve(tenantId, profile, question, CANDIDATES);
        List<RetrievedChunk> byWords = fullTextRetriever.retrieve(tenantId, profile, question, CANDIDATES);
        if (!relevant(profile.retrieval(), byMeaning, byWords)) {
            return List.of();
        }
        return ReciprocalRankFusion.fuse(List.of(byMeaning, byWords), limit);
    }


    static boolean relevant(LanguageProfile.Retrieval thresholds, List<RetrievedChunk> byMeaning, List<RetrievedChunk> byWords) {
        boolean meaning = !byMeaning.isEmpty() && byMeaning.getFirst().score() >= thresholds.minVectorSimilarity();
        boolean words = !byWords.isEmpty() && byWords.getFirst().score() >= thresholds.minTextScore();
        return meaning || words;
    }
}
