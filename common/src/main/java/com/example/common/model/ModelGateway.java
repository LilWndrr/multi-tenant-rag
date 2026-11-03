package com.example.common.model;

import java.util.List;
import java.util.function.Consumer;

public interface ModelGateway {

    ModelChatResponse chat(ModelCall call, List<ChatMessage> messages);

    ModelChatResponse stream(ModelCall call, Consumer<String> onToken , List<ChatMessage> messages);

    ModelEmbedResponse embed(ModelCall call, List<String> texts);
}
