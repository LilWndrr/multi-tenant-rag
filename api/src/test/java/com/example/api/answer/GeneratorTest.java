package com.example.api.answer;

import com.example.api.retrieve.RetrievedChunk;
import com.example.common.model.ChatMessage;
import com.example.common.model.ModelCall;
import com.example.common.model.ModelChatResponse;
import com.example.common.model.ModelGateway;
import com.example.common.model.Usage;
import com.example.common.profile.LanguageProfile;
import com.example.common.profile.LanguageProfiles;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// The gateway is replaced; GeneratorLiveTest checks what a real model does with the prompt
class GeneratorTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID DOC = UUID.randomUUID();
    private static final List<RetrievedChunk> SOURCES = List.of(
            new RetrievedChunk(7, DOC, "job.md", "Jobs", "A Job retries a failed Pod six times.", 0.9f),
            new RetrievedChunk(8, DOC, "pod.md", "Pods", "A Pod is a group of containers.", 0.8f));

    private final ModelGateway gateway = mock(ModelGateway.class);
    private final Generator generator = new Generator(gateway, "some/chat-model");
    private final LanguageProfile en;
    private final List<String> tokens = new ArrayList<>();

    GeneratorTest() throws IOException {
        en = new LanguageProfiles().get("en");
    }

    private void replies(String text) {
        when(gateway.stream(any(), any(), anyList())).thenAnswer(inv -> {
            Consumer<String> onToken = inv.getArgument(1);
            onToken.accept(text);
            return new ModelChatResponse(text, "some/chat-model", new Usage(10, 5, 0, null));
        });
    }

    // the Task 22 gate gave no sources: answer without a model call
    @Test
    void noSources_answersNoAnswer_withoutCallingTheModel() {
        Answer answer = generator.generate(TENANT, en, "What's the best pizza in Rome?", List.of(), tokens::add);

        assertThat(answer.answered()).isFalse();
        assertThat(answer.text()).isEqualTo(en.prompts().noAnswer());
        assertThat(answer.citations()).isEmpty();
        assertThat(tokens).containsExactly(en.prompts().noAnswer());
        verifyNoInteractions(gateway);
    }

    @Test
    void anAnswer_streamsItsTokens_andReturnsTheSourcesItCites() {
        replies("A Job retries six times [1].");

        Answer answer = generator.generate(TENANT, en, "How often does a Job retry?", SOURCES, tokens::add);

        assertThat(answer.answered()).isTrue();
        assertThat(answer.text()).isEqualTo("A Job retries six times [1].");
        assertThat(answer.citations()).singleElement().satisfies(c -> {
            assertThat(c.number()).isEqualTo(1);
            assertThat(c.chunk().id()).isEqualTo(7);
        });
        assertThat(tokens).containsExactly("A Job retries six times [1].");
    }

    @Test
    void theModelSayingTheSourcesDontContainIt_isNotAnswered_andCitesNothing() {
        replies(en.prompts().noAnswer());

        Answer answer = generator.generate(TENANT, en, "How do I configure Docker Swarm?", SOURCES, tokens::add);

        assertThat(answer.answered()).isFalse();
        assertThat(answer.citations()).isEmpty();
    }

    @Test
    void callsTheConfiguredModel_withThePromptForThisProfile() {
        replies("ok [2]");

        generator.generate(TENANT, en, "What is a Pod?", SOURCES, tokens::add);

        List<ChatMessage> expected = AnswerPrompt.build(en, "What is a Pod?", SOURCES);
        verify(gateway).stream(eq(new ModelCall(TENANT, "answer", "some/chat-model")), any(), eq(expected));
    }
}
