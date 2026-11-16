package com.example.api.retrieve;

import com.example.common.model.ModelCall;
import com.example.common.model.ModelGateway;
import com.example.common.model.ModelRerankResponse;
import com.example.common.profile.LanguageProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;


@Primary
@Component("model-reranker")
public class ModelReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(ModelReranker.class);

    private final ModelGateway modelGateway;
    private final NoOpReranker noOp;

    public ModelReranker(ModelGateway modelGateway, NoOpReranker noOp) {
        this.modelGateway = modelGateway;
        this.noOp = noOp;
    }

    @Override
    public List<RetrievedChunk> rerank(UUID tenantId, LanguageProfile profile, String question,
                                       List<RetrievedChunk> candidates, int limit) {
        if (profile.reranker() == null || candidates.isEmpty()) {
            return noOp.rerank(tenantId, profile, question, candidates, limit);
        }
        List<String> texts = candidates.stream().map(ModelReranker::textOf).toList();
        try {
            ModelRerankResponse response = modelGateway.rerank(
                    new ModelCall(tenantId, "rerank", profile.reranker()), question, texts);
            return order(candidates, response.scores(), limit);
        } catch (RuntimeException e) {
            log.warn("Reranking failed for tenant {} ({} candidates), keeping the retriever's order: {}",
                    tenantId, candidates.size(), e.getMessage());
            return noOp.rerank(tenantId, profile, question, candidates, limit);
        }
    }


    static String textOf(RetrievedChunk chunk) {
        return chunk.headingPath() == null || chunk.headingPath().isBlank()
                ? chunk.content()
                : chunk.headingPath() + "\n\n" + chunk.content();
    }

    static List<RetrievedChunk> order(List<RetrievedChunk> candidates, List<ModelRerankResponse.Score> scores, int limit) {
        if (scores.size() != candidates.size()) {
            throw new IllegalStateException("expected " + candidates.size() + " scores, got " + scores.size());
        }
        boolean[] seen = new boolean[candidates.size()];
        for (ModelRerankResponse.Score score : scores) {
            if (score.index() < 0 || score.index() >= candidates.size() || seen[score.index()]) {
                throw new IllegalStateException("score for an unknown or repeated candidate: " + score.index());
            }
            seen[score.index()] = true;
        }
        List<ModelRerankResponse.Score> sorted = new ArrayList<>(scores);
        // equal scores keep the retriever's order
        sorted.sort(Comparator.comparingDouble(ModelRerankResponse.Score::relevance).reversed()
                .thenComparingInt(ModelRerankResponse.Score::index));
        return sorted.stream()
                .limit(limit)
                .map(s -> {
                    RetrievedChunk c = candidates.get(s.index());
                    return new RetrievedChunk(c.id(), c.documentId(), c.fileName(), c.headingPath(), c.content(),
                            (float) s.relevance());
                })
                .toList();
    }
}
