package com.example.api.chat;

import com.example.api.answer.Answer;
import com.example.api.answer.Generator;
import com.example.api.retrieve.Reranker;
import com.example.api.retrieve.RetrievedChunk;
import com.example.api.retrieve.Retriever;
import com.example.common.profile.LanguageProfile;
import com.example.common.profile.LanguageProfiles;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID DOC = UUID.randomUUID();

    private final Retriever retriever = mock(Retriever.class);
    private final Reranker reranker = mock(Reranker.class);
    private final Generator generator = mock(Generator.class);
    private final ChatService service = new ChatService(retriever, reranker, generator, 6);
    private final LanguageProfile en;

    ChatServiceTest() throws IOException {
        en = new LanguageProfiles().get("en");
    }

    private static RetrievedChunk chunk(long id) {
        return new RetrievedChunk(id, DOC, id + ".md", "", "text " + id, 0.5f);
    }

    // 20 candidates from the retriever, the reranker keeps 6, and only those reach the prompt
    @Test
    void retrieves20_reranksTo6_andAnswersFromThose() {
        List<RetrievedChunk> candidates = List.of(chunk(1), chunk(2), chunk(3));
        List<RetrievedChunk> best = List.of(chunk(3), chunk(1));
        Consumer<String> onToken = token -> { };
        Answer expected = new Answer("x [1]", true, List.of(new Answer.Citation(1, chunk(3))));
        when(retriever.retrieve(TENANT, en, "q", 20)).thenReturn(candidates);
        when(reranker.rerank(TENANT, en, "q", candidates, 6)).thenReturn(best);
        when(generator.generate(TENANT, en, "q", best, onToken)).thenReturn(expected);

        assertThat(service.answer(TENANT, en, "q", onToken)).isSameAs(expected);
    }

    // the gate found nothing: the generator still runs, and answers no-answer without a model call (GeneratorTest)
    @Test
    void noCandidates_stillGoesToTheGenerator() {
        when(retriever.retrieve(any(), any(), any(), any(Integer.class))).thenReturn(List.of());
        when(reranker.rerank(any(), any(), any(), any(), any(Integer.class))).thenReturn(List.of());

        service.answer(TENANT, en, "pizza?", token -> { });

        verify(generator).generate(any(), any(), any(), any(), any());
    }
}
