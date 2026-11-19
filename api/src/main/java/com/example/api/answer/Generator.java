package com.example.api.answer;

import com.example.api.retrieve.RetrievedChunk;
import com.example.common.model.ModelCall;
import com.example.common.model.ModelChatResponse;
import com.example.common.model.ModelGateway;
import com.example.common.profile.LanguageProfile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

// Answers a question from the reranked sources only, citing them as [n] (docs/decisions.md, Task 24).
// Tokens go to onToken as they arrive; the returned Answer has the full text and the sources it cited.
@Component
public class Generator {

    private final ModelGateway modelGateway;
    private final String chatModel;

    public Generator(ModelGateway modelGateway, @Value("${rag.models.chat}") String chatModel) {
        this.modelGateway = modelGateway;
        this.chatModel = chatModel;
    }

    public Answer generate(UUID tenantId, LanguageProfile profile, String question, List<RetrievedChunk> sources,
                           Consumer<String> onToken) {
        String noAnswer = profile.prompts().noAnswer();
        // the retriever found nothing relevant: nothing to answer from, so no model call
        if (sources.isEmpty()) {
            onToken.accept(noAnswer);
            return new Answer(noAnswer, false, List.of());
        }
        ModelChatResponse response = modelGateway.stream(new ModelCall(tenantId, "answer", chatModel), onToken,
                AnswerPrompt.build(profile, question, sources));
        String text = response.chat();
        if (Citations.isNoAnswer(text, noAnswer)) {
            return new Answer(text, false, List.of());
        }
        return new Answer(text, true, Citations.of(text, sources));
    }
}
