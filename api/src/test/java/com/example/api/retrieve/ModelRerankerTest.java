package com.example.api.retrieve;

import com.example.common.model.ModelCall;
import com.example.common.model.ModelGateway;
import com.example.common.model.ModelRerankResponse;
import com.example.common.model.ModelRerankResponse.Score;
import com.example.common.model.Usage;
import com.example.common.profile.LanguageProfile;
import com.example.common.profile.LanguageProfiles;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// The gateway is replaced; the real HTTP call is tested in OpenRouterRerankClientTest
class ModelRerankerTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID DOC = UUID.randomUUID();
    private static final Usage NO_USAGE = new Usage(0, 0, 0, null);

    private final ModelGateway gateway = mock(ModelGateway.class);
    private final ModelReranker reranker = new ModelReranker(gateway, new NoOpReranker());
    private final LanguageProfile withReranker;
    private final LanguageProfile without;

    ModelRerankerTest() throws IOException {
        LanguageProfile en = new LanguageProfiles().get("en");
        withReranker = new LanguageProfile(en.id(), en.embeddingModel(), en.embeddingDimensions(), "some/reranker",
                en.chunk(), en.textSearchConfig(), en.retrieval(), en.prompts());
        without = new LanguageProfile(en.id(), en.embeddingModel(), en.embeddingDimensions(), null,
                en.chunk(), en.textSearchConfig(), en.retrieval(), en.prompts());
    }

    @Test
    void ordersByTheModelsScores_andKeepsTheLimit() {
        scores(new Score(0, 0.1), new Score(1, 0.9), new Score(2, 0.5), new Score(3, 0.3));

        List<RetrievedChunk> result = reranker.rerank(TENANT, withReranker, "q", chunks(10, 11, 12, 13), 3);

        assertThat(result).extracting(RetrievedChunk::id).containsExactly(11L, 12L, 13L);
        assertThat(result.getFirst().score()).isEqualTo(0.9f);
        assertThat(result.getFirst().content()).isEqualTo("chunk 11");
    }

    @Test
    void sendsTheQuestion_andEachChunkWithItsHeading_toTheProfilesModel() {
        scores(new Score(0, 0.5), new Score(1, 0.4));
        List<RetrievedChunk> candidates = List.of(
                new RetrievedChunk(1, DOC, "a.md", "Networking > Ports", "10250", 0.03f),
                new RetrievedChunk(2, DOC, "b.md", "", "plain", 0.02f));

        reranker.rerank(TENANT, withReranker, "which port?", candidates, 8);

        verify(gateway).rerank(eq(new ModelCall(TENANT, "rerank", "some/reranker")), eq("which port?"),
                eq(List.of("Networking > Ports\n\n10250", "plain")));
    }

    @Test
    void equalScores_keepTheRetrieversOrder() {
        scores(new Score(2, 0.5), new Score(0, 0.5), new Score(1, 0.5));

        assertThat(reranker.rerank(TENANT, withReranker, "q", chunks(7, 8, 9), 3))
                .extracting(RetrievedChunk::id).containsExactly(7L, 8L, 9L);
    }

    @Test
    void aProfileWithoutReranker_keepsTheRetrieversOrder_withoutACall() {
        assertThat(reranker.rerank(TENANT, without, "q", chunks(3, 2, 1), 2))
                .extracting(RetrievedChunk::id).containsExactly(3L, 2L);
        verifyNoInteractions(gateway);
    }

    @Test
    void noCandidates_noCall() {
        assertThat(reranker.rerank(TENANT, withReranker, "q", List.of(), 5)).isEmpty();
        verifyNoInteractions(gateway);
    }

    // timeout, 429, provider down: the answer goes ahead with the retriever's order
    @Test
    void aFailedCall_fallsBackToTheRetrieversOrder() {
        when(gateway.rerank(any(), anyString(), anyList())).thenThrow(new RuntimeException("rerank returned HTTP 429"));

        assertThat(reranker.rerank(TENANT, withReranker, "q", chunks(3, 2, 1), 2))
                .extracting(RetrievedChunk::id).containsExactly(3L, 2L);
    }

    @Test
    void aResponseThatDoesNotMatchTheCandidates_fallsBack() {
        scores(new Score(0, 0.9), new Score(0, 0.8));          // a repeat, and candidate 1 missing
        assertThat(reranker.rerank(TENANT, withReranker, "q", chunks(5, 6), 2))
                .extracting(RetrievedChunk::id).containsExactly(5L, 6L);

        scores(new Score(0, 0.9), new Score(7, 0.8));          // an index that was never sent
        assertThat(reranker.rerank(TENANT, withReranker, "q", chunks(5, 6), 2))
                .extracting(RetrievedChunk::id).containsExactly(5L, 6L);

        scores(new Score(1, 0.9));                            // fewer scores than candidates
        assertThat(reranker.rerank(TENANT, withReranker, "q", chunks(5, 6), 2))
                .extracting(RetrievedChunk::id).containsExactly(5L, 6L);
    }

    private void scores(Score... scores) {
        when(gateway.rerank(any(), anyString(), anyList()))
                .thenReturn(new ModelRerankResponse(List.of(scores), "some/reranker", NO_USAGE));
    }

    private static List<RetrievedChunk> chunks(long... ids) {
        return java.util.stream.LongStream.of(ids)
                .mapToObj(id -> new RetrievedChunk(id, DOC, "f.md", "", "chunk " + id, 0.01f))
                .toList();
    }
}
