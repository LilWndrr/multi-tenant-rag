package com.example.common.model.openrouter;


import com.example.common.model.*;
import com.openai.core.JsonNumber;
import com.openai.core.JsonValue;
import com.openai.models.completions.CompletionUsage;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import org.springframework.ai.embedding.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Component
public class OpenRouterModelGateway implements ModelGateway {

    private static final String PROVIDER = "openrouter";
    private static final Usage NO_USAGE = new Usage(0, 0, 0, null);

    private final ChatModel chatModel;
    private final EmbeddingModel embeddingModel;
    private final ModelCallLogger callLogger;
    private final OpenRouterRetry retry;

    public OpenRouterModelGateway(ChatModel chatModel, EmbeddingModel embeddingModel,
                                  ModelCallLogger callLogger, OpenRouterRetry retry) {
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.callLogger = callLogger;
        this.retry = retry;
    }

    @Override
    public ModelChatResponse chat(ModelCall call, List<ChatMessage> messages) {
        Prompt prompt = new Prompt(toSpringMessages(messages),
                OpenAiChatOptions.builder().model(call.model()).build());
        return retry.execute(() -> chatAttempt(call, prompt));
    }

    // one attempt = one provider request = one model_calls row
    private ModelChatResponse chatAttempt(ModelCall call, Prompt prompt) {
        long start = System.nanoTime();
        try {
            ChatResponse response = chatModel.call(prompt);
            int latencyMs = elapsedMs(start);

            ChatResponseMetadata metadata = response.getMetadata();
            String model = metadata.getModel() != null ? metadata.getModel() : call.model();
            Usage usage = toUsage(metadata.getUsage());

            callLogger.createModelCall(call, PROVIDER, model, "ok", usage, latencyMs);
            return new ModelChatResponse(response.getResult().getOutput().getText(), model, usage);
        } catch (RuntimeException e) {
            callLogger.createModelCall(call, PROVIDER, call.model(), "error", NO_USAGE, elapsedMs(start));
            throw e;
        }
    }

    @Override
    public ModelChatResponse stream(ModelCall call, Consumer<String> onToken , List<ChatMessage> messages) {
        Prompt prompt = new Prompt(toSpringMessages(messages),
                OpenAiChatOptions.builder().streamUsage(true).model(call.model()).build());
        AtomicBoolean delivered = new AtomicBoolean(false);
        try {
            return retry.execute(() -> streamAttempt(call, prompt, onToken, delivered));
        } catch (OpenRouterRetry.PartialStreamException e) {
            throw (RuntimeException) e.getCause();
        }
    }

    private ModelChatResponse streamAttempt(ModelCall call, Prompt prompt, Consumer<String> onToken,
                                            AtomicBoolean delivered) {
        long start =  System.nanoTime();

        StringBuilder fullText = new StringBuilder();
        AtomicReference<org.springframework.ai.chat.metadata.Usage> lastUsage = new AtomicReference<>();
        AtomicReference<String> reportedModel = new AtomicReference<>();

        try {
            chatModel.stream(prompt)
                    .doOnNext(chunk -> {
                        if (chunk.getResult() != null && chunk.getResult().getOutput() != null) {
                            String piece = chunk.getResult().getOutput().getText();
                            if (piece != null && !piece.isEmpty()) {
                                delivered.set(true);
                                onToken.accept(piece);
                                fullText.append(piece);
                            }
                        }
                        ChatResponseMetadata metadata = chunk.getMetadata();
                        if (metadata == null) {
                            return;
                        }
                        if (metadata.getModel() != null && !metadata.getModel().isEmpty()) {
                            reportedModel.set(metadata.getModel());
                        }
                        org.springframework.ai.chat.metadata.Usage usage = metadata.getUsage();
                        if (usage != null && orZero(usage.getPromptTokens()) > 0) {
                            lastUsage.set(usage);
                        }
                    })
                    .blockLast();
            int latencyMs = elapsedMs(start);

            String model = reportedModel.get() != null ? reportedModel.get() : call.model();
            Usage usage = toUsage(lastUsage.get());

            callLogger.createModelCall(call, PROVIDER, model, "ok", usage, latencyMs);
            return new ModelChatResponse(fullText.toString(), model, usage);
        }catch (RuntimeException e){
            callLogger.createModelCall(call, PROVIDER, call.model(), "error", NO_USAGE, elapsedMs(start));
            throw delivered.get() ? new OpenRouterRetry.PartialStreamException(e) : e;
        }

    }

    @Override
    public ModelEmbedResponse embed(ModelCall call, List<String> texts) {
        EmbeddingRequest request = new EmbeddingRequest(texts, OpenAiEmbeddingOptions.builder().model(call.model()).build());
        return retry.execute(() -> embedAttempt(call, request));
    }

    private ModelEmbedResponse embedAttempt(ModelCall call, EmbeddingRequest request) {
        long start = System.nanoTime();

        try {
            EmbeddingResponse embeddingResponse = embeddingModel.call(request);
            int latencyMs = elapsedMs(start);

        EmbeddingResponseMetadata metadata= embeddingResponse.getMetadata();

        String model = metadata.getModel() !=null ? metadata.getModel(): call.model();
        List<float[]> embeddings = embeddingResponse.getResults().stream().map(Embedding::getOutput).toList();
        Usage usage = toUsage(metadata.getUsage());
        callLogger.createModelCall(call, PROVIDER, model, "ok", usage, latencyMs);
        return new ModelEmbedResponse(embeddings,model,usage);

        }catch (RuntimeException e){
            callLogger.createModelCall(call, PROVIDER, call.model(), "error", NO_USAGE, elapsedMs(start));
            throw e;
        }


    }

    private static List<Message> toSpringMessages(List<ChatMessage> messages) {
        return messages.stream()
                .map(m -> switch (m.role()) {
                    case SYSTEM -> (Message) new SystemMessage(m.content());
                    case USER -> new UserMessage(m.content());
                    case ASSISTANT -> new AssistantMessage(m.content());
                })
                .toList();
    }

    private static Usage toUsage(org.springframework.ai.chat.metadata.Usage usage) {
        if (usage == null) {
            return NO_USAGE;
        }
        Long cached = usage.getCacheReadInputTokens();
        return new Usage(
                orZero(usage.getPromptTokens()),
                orZero(usage.getCompletionTokens()),
                cached == null ? 0 : cached.intValue(),
                costOf(usage.getNativeUsage()));
    }


    private static BigDecimal costOf(Object nativeUsage) {
        Map<String, JsonValue> extra = additionalPropertiesOf(nativeUsage);
        if (extra != null && extra.get("cost") instanceof JsonNumber cost) {
            // via toString, so a double never rounds the amount
            return new BigDecimal(cost.value().toString());
        }
        return null;
    }


    private static Map<String, JsonValue> additionalPropertiesOf(Object nativeUsage) {
        return switch (nativeUsage) {
            case CompletionUsage chat -> chat._additionalProperties();
            case CreateEmbeddingResponse.Usage embedding -> embedding._additionalProperties();
            case null, default -> null;
        };
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static int elapsedMs(long startNanos) {
        return (int) ((System.nanoTime() - startNanos) / 1_000_000);
    }
}
