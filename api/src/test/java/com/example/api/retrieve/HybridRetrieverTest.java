package com.example.api.retrieve;

import com.example.common.profile.LanguageProfile;
import com.example.common.profile.LanguageProfiles;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// The two retrievers are replaced; their real queries are tested in VectorRetrieverTest and FullTextRetrieverTest.
// The thresholds come from the real profile, so a change there that breaks these cases shows up here.
class HybridRetrieverTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID DOC = UUID.randomUUID();

    private final VectorRetriever vector = mock(VectorRetriever.class);
    private final FullTextRetriever fullText = mock(FullTextRetriever.class);
    private final HybridRetriever hybrid = new HybridRetriever(vector, fullText);
    private final LanguageProfile en;

    HybridRetrieverTest() throws IOException {
        en = new LanguageProfiles().get("en");
    }

    // what an off-topic question looked like on the demo tenant: best similarity about 0.1, a stray common word
    @Test
    void anOffTopicQuestion_getsNoSources() {
        answers(chunks(0.10f, 1, 2, 3), chunks(0.20f, 4, 5));

        assertThat(hybrid.retrieve(TENANT, en, "What's the best pizza in Rome?", 8)).isEmpty();
    }

    @Test
    void aGoodVectorMatchAlone_isEnough() {
        answers(chunks(0.60f, 1, 2), List.of());

        assertThat(hybrid.retrieve(TENANT, en, "q", 8)).extracting(RetrievedChunk::id).containsExactly(1L, 2L);
    }

    // the port-number case: vector search finds nothing close, full-text matches the rare term exactly
    @Test
    void aGoodFullTextMatchAlone_isEnough() {
        answers(chunks(0.20f, 1, 2), chunks(1.0f, 42));

        assertThat(hybrid.retrieve(TENANT, en, "What runs on port 10250?", 8)).extracting(RetrievedChunk::id).contains(42L);
    }

    @Test
    void scoresExactlyAtTheThreshold_count() {
        answers(chunks(en.retrieval().minVectorSimilarity().floatValue(), 1), List.of());

        assertThat(hybrid.retrieve(TENANT, en, "q", 8)).hasSize(1);
    }

    @Test
    void noResultsAtAll_getsNoSources() {
        answers(List.of(), List.of());

        assertThat(hybrid.retrieve(TENANT, en, "q", 8)).isEmpty();
    }

    @Test
    void theListsAreMerged_bothRankingAChunkHighlyPutsItFirst() {
        answers(chunks(0.60f, 1, 2, 3), chunks(0.90f, 9, 2, 8));

        List<RetrievedChunk> results = hybrid.retrieve(TENANT, en, "q", 8);

        assertThat(results.getFirst().id()).isEqualTo(2);
        assertThat(results).extracting(RetrievedChunk::id).doesNotHaveDuplicates().contains(1L, 3L, 8L, 9L);
    }

    // each retriever contributes its 20 whatever the caller asks for; the caller's limit cuts the merged list
    @Test
    void asksEachRetrieverForTwentyCandidates_andCutsTheMergeToTheLimit() {
        answers(chunks(0.60f, 1, 2, 3, 4, 5, 6), chunks(0.90f, 7, 8, 9, 10, 11, 12));

        assertThat(hybrid.retrieve(TENANT, en, "q", 5)).hasSize(5);
        verify(vector).retrieve(TENANT, en, "q", HybridRetriever.CANDIDATES);
        verify(fullText).retrieve(TENANT, en, "q", HybridRetriever.CANDIDATES);
    }

    private void answers(List<RetrievedChunk> byMeaning, List<RetrievedChunk> byWords) {
        when(vector.retrieve(any(), any(), anyString(), anyInt())).thenReturn(byMeaning);
        when(fullText.retrieve(any(), any(), anyString(), anyInt())).thenReturn(byWords);
    }

    // best first: the first chunk gets the given score, the rest a little less each
    private static List<RetrievedChunk> chunks(float best, long... ids) {
        return java.util.stream.IntStream.range(0, ids.length)
                .mapToObj(i -> new RetrievedChunk(ids[i], DOC, "f.md", "", "chunk " + ids[i], best - i * 0.01f))
                .toList();
    }
}
