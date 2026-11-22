package com.example.api.chat;

import com.example.api.answer.Answer;
import com.example.api.answer.Generator;
import com.example.api.retrieve.Reranker;
import com.example.api.retrieve.RetrievedChunk;
import com.example.api.retrieve.Retriever;
import com.example.common.profile.LanguageProfile;
import com.example.common.profile.LanguageProfiles;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID DOC = UUID.randomUUID();

    private final Retriever retriever = mock(Retriever.class);
    private final Reranker reranker = mock(Reranker.class);
    private final Generator generator = mock(Generator.class);
    private final AnswerCache cache = mock(AnswerCache.class);
    private final DocsVersions docsVersions = mock(DocsVersions.class);
    private final ChatService service = new ChatService(retriever, reranker, generator, cache, docsVersions, 6, "m/chat");
    private final LanguageProfile en;
    private final List<String> tokens = new ArrayList<>();

    ChatServiceTest() throws IOException {
        en = new LanguageProfiles().get("en");
        when(docsVersions.of(TENANT)).thenReturn(7L);
        when(cache.get(anyString())).thenReturn(Optional.empty());
    }

    private static RetrievedChunk chunk(long id) {
        return new RetrievedChunk(id, DOC, id + ".md", "", "text " + id, 0.5f);
    }

    // 20 candidates from the retriever, the reranker keeps 6, and only those reach the prompt
    @Test
    void aMiss_retrieves20_reranksTo6_answersFromThose_andStoresTheAnswer() {
        List<RetrievedChunk> candidates = List.of(chunk(1), chunk(2), chunk(3));
        List<RetrievedChunk> best = List.of(chunk(3), chunk(1));
        Consumer<String> onToken = tokens::add;
        when(retriever.retrieve(TENANT, en, "q", 20)).thenReturn(candidates);
        when(reranker.rerank(TENANT, en, "q", candidates, 6)).thenReturn(best);
        when(generator.generate(TENANT, en, "q", best, onToken))
                .thenReturn(new Answer("x [1]", true, List.of(new Answer.Citation(1, chunk(3)))));

        ChatReply reply = service.answer(TENANT, en, "q", onToken);

        assertThat(reply.cached()).isFalse();
        assertThat(reply.text()).isEqualTo("x [1]");
        assertThat(reply.citations().citations()).extracting(ChatEvents.Citation::chunkId).containsExactly(3L);
        ArgumentCaptor<AnswerCache.Entry> stored = ArgumentCaptor.forClass(AnswerCache.Entry.class);
        verify(cache).put(anyString(), stored.capture());
        assertThat(stored.getValue().text()).isEqualTo("x [1]");
        assertThat(stored.getValue().citations()).isEqualTo(reply.citations());
    }

    @Test
    void aHit_sendsTheCachedText_withoutRetrievingOrCallingAModel() {
        ChatEvents.Citations citations = new ChatEvents.Citations(true,
                List.of(new ChatEvents.Citation(1, 42, DOC, "a.md", "", "snippet")));
        when(cache.get(anyString())).thenReturn(Optional.of(new AnswerCache.Entry("cached [1]", citations)));

        ChatReply reply = service.answer(TENANT, en, "q", tokens::add);

        assertThat(reply.cached()).isTrue();
        assertThat(reply.citations()).isEqualTo(citations);
        assertThat(tokens).containsExactly("cached [1]");
        verifyNoInteractions(retriever, reranker, generator);
        verify(cache, never()).put(anyString(), any());
    }

    // the docs version is in the key: after a change to the tenant's chunks, the same question has a new key
    @Test
    void theKey_changesWithTheDocsVersion_notWithCaseOrAFinalQuestionMark() {
        when(generator.generate(any(), any(), any(), any(), any())).thenReturn(new Answer("no", false, List.of()));
        when(retriever.retrieve(any(), any(), any(), any(Integer.class))).thenReturn(List.of());
        when(reranker.rerank(any(), any(), any(), any(), any(Integer.class))).thenReturn(List.of());

        service.answer(TENANT, en, "What is a Pod?", tokens::add);
        service.answer(TENANT, en, "  what is a   pod ", tokens::add);
        when(docsVersions.of(TENANT)).thenReturn(8L);
        service.answer(TENANT, en, "What is a Pod?", tokens::add);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(cache, times(3)).get(keys.capture());
        assertThat(keys.getAllValues().get(0)).isEqualTo(keys.getAllValues().get(1));
        assertThat(keys.getAllValues().get(2)).isNotEqualTo(keys.getAllValues().get(0));
    }

    // an answer cut short (the model failed, or the client left) must not be served to the next person
    @Test
    void anAnswerThatFails_isNotStored() {
        when(retriever.retrieve(any(), any(), any(), any(Integer.class))).thenReturn(List.of(chunk(1)));
        when(reranker.rerank(any(), any(), any(), any(), any(Integer.class))).thenReturn(List.of(chunk(1)));
        when(generator.generate(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("provider down"));

        assertThatThrownBy(() -> service.answer(TENANT, en, "q", tokens::add)).isInstanceOf(IllegalStateException.class);

        verify(cache, never()).put(anyString(), any());
    }

    // the gate found nothing: the generator still runs, and answers no-answer without a model call (GeneratorTest)
    @Test
    void noCandidates_stillGoesToTheGenerator() {
        when(retriever.retrieve(any(), any(), any(), any(Integer.class))).thenReturn(List.of());
        when(reranker.rerank(any(), any(), any(), any(), any(Integer.class))).thenReturn(List.of());
        when(generator.generate(any(), any(), any(), any(), any())).thenReturn(new Answer("no", false, List.of()));

        service.answer(TENANT, en, "pizza?", tokens::add);

        verify(generator).generate(any(), any(), any(), any(), any());
    }
}
