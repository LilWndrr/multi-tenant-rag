package com.example.api.retrieve;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ReciprocalRankFusionTest {

    private static final UUID DOC = UUID.randomUUID();

    @Test
    void aChunkBothListsRankHighly_comesFirst() {
        List<RetrievedChunk> byMeaning = chunks(1, 2, 3);
        List<RetrievedChunk> byWords = chunks(9, 2, 8);

        List<RetrievedChunk> fused = ReciprocalRankFusion.fuse(List.of(byMeaning, byWords), 10);

        assertThat(fused.getFirst().id()).isEqualTo(2);   // 1/62 + 1/62 beats 1/61 alone
    }

    @Test
    void scoresAreTheSumOf_oneOverKPlusRank() {
        List<RetrievedChunk> fused = ReciprocalRankFusion.fuse(List.of(chunks(1, 2), chunks(2)), 10);

        assertThat(fused).extracting(RetrievedChunk::id).containsExactly(2L, 1L);
        assertThat(fused.get(0).score()).isCloseTo((float) (1.0 / 62 + 1.0 / 61), within(1e-6f));
        assertThat(fused.get(1).score()).isCloseTo((float) (1.0 / 61), within(1e-6f));
    }

    // the port-number case from Task 21: only full-text finds it, and it must still make the list
    @Test
    void aChunkOnlyOneListFound_isKept() {
        List<RetrievedChunk> fused = ReciprocalRankFusion.fuse(List.of(chunks(1, 2, 3), chunks(42)), 10);

        assertThat(fused).extracting(RetrievedChunk::id).contains(42L);
    }

    @Test
    void eachChunkAppearsOnce_withItsTextFromTheLists() {
        RetrievedChunk chunk = new RetrievedChunk(7, DOC, "a.md", "Setup", "text", 0.9f);

        List<RetrievedChunk> fused = ReciprocalRankFusion.fuse(List.of(List.of(chunk), List.of(chunk)), 10);

        assertThat(fused).singleElement().satisfies(f -> {
            assertThat(f.id()).isEqualTo(7);
            assertThat(f.fileName()).isEqualTo("a.md");
            assertThat(f.headingPath()).isEqualTo("Setup");
            assertThat(f.content()).isEqualTo("text");
        });
    }

    // 5 is first in one list, 6 is first in the other: equal sums, then lower id
    @Test
    void tiesAreBrokenTheSameWayEveryTime() {
        List<RetrievedChunk> fused = ReciprocalRankFusion.fuse(List.of(chunks(6, 1), chunks(5, 2)), 10);

        assertThat(fused).extracting(RetrievedChunk::id).startsWith(5L, 6L);
    }

    @Test
    void returnsAtMostTheLimit() {
        assertThat(ReciprocalRankFusion.fuse(List.of(chunks(LongStream.rangeClosed(1, 20).toArray()),
                chunks(LongStream.rangeClosed(21, 40).toArray())), 8)).hasSize(8);
    }

    @Test
    void emptyLists_giveAnEmptyResult() {
        assertThat(ReciprocalRankFusion.fuse(List.of(List.of(), List.of()), 10)).isEmpty();
    }

    private static List<RetrievedChunk> chunks(long... ids) {
        return LongStream.of(ids).mapToObj(id -> new RetrievedChunk(id, DOC, "f.md", "", "chunk " + id, 0.5f)).toList();
    }
}
