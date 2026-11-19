package com.example.api.answer;

import com.example.api.retrieve.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

class CitationsTest {

    private static final UUID DOC = UUID.randomUUID();
    private static final String NO = "I could not find an answer to that in your documents.";
    private static final List<RetrievedChunk> SIX = LongStream.rangeClosed(101, 106)
            .mapToObj(id -> new RetrievedChunk(id, DOC, id + ".md", "", "text", 0.5f)).toList();

    @Test
    void citationsInOrderOfFirstMention_eachOnce_mappedToTheirSource() {
        List<Answer.Citation> citations = Citations.of("Jobs retry [3]. Pods restart [1]. Both [3].", SIX);

        assertThat(citations).extracting(Answer.Citation::number).containsExactly(3, 1);
        assertThat(citations.getFirst().chunk().id()).isEqualTo(103);
    }

    @Test
    void groupedAndAdjacentMarkers_bothCount() {
        assertThat(Citations.of("a [1, 4] b [2][5]", SIX)).extracting(Answer.Citation::number).containsExactly(1, 4, 2, 5);
    }

    // six sources: [9] and [0] point at nothing, and a made-up source must not become a link
    @Test
    void numbersWithoutASource_areDropped() {
        assertThat(Citations.of("x [9] y [0] z [2] w [99999999999]", SIX)).extracting(Answer.Citation::number).containsExactly(2);
    }

    @Test
    void noMarkers_noCitations() {
        assertThat(Citations.of("An answer without sources, or a list [a] like [ 1 ].", SIX)).isEmpty();
    }

    @Test
    void theNoAnswerSentence_isRecognised_withSmallDifferencesForgiven() {
        assertThat(Citations.isNoAnswer(NO, NO)).isTrue();
        assertThat(Citations.isNoAnswer("  " + NO + "\n", NO)).isTrue();
        assertThat(Citations.isNoAnswer("I could not find an answer to that in your documents", NO)).isTrue();
        assertThat(Citations.isNoAnswer("\"I COULD NOT find an answer to that in your documents.\"", NO)).isTrue();
        assertThat(Citations.isNoAnswer("Belgelerinizde bu soruya bir yanıt bulamadım", "Belgelerinizde bu soruya bir yanıt bulamadım.")).isTrue();
    }

    // a real answer that happens to start with the sentence is still an answer
    @Test
    void anythingMoreThanTheSentence_isAnAnswer() {
        assertThat(Citations.isNoAnswer(NO + " However, Jobs retry [1].", NO)).isFalse();
        assertThat(Citations.isNoAnswer("Jobs retry six times [1].", NO)).isFalse();
    }
}
