package com.example.common.model;

import java.util.List;
import java.util.function.Consumer;

public interface ModelGateway {

    ModelChatResponse chat(ModelCall call, List<ChatMessage> messages);

    ModelChatResponse stream(ModelCall call, Consumer<String> onToken , List<ChatMessage> messages);

    ModelEmbedResponse embed(ModelCall call, List<String> texts);

    // Scores how well each document answers the query (a cross-encoder). One attempt, no retry: the caller has a
    // fallback, and waiting to retry would only make the answer later.
    ModelRerankResponse rerank(ModelCall call, String query, List<String> documents);
}
