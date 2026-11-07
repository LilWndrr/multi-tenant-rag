package com.example.worker;

import com.example.common.model.ChatMessage;
import com.example.common.model.ModelCall;
import com.example.common.model.ModelChatResponse;
import com.example.common.model.ModelEmbedResponse;
import com.example.common.model.ModelGateway;
import com.example.common.model.Usage;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

// So pipeline tests never call a real provider. Vectors have the size of the model's index (V4).
public class FakeModelGateway implements ModelGateway {

    static final int VECTOR_SIZE = 2048;

    private final AtomicInteger embedCalls = new AtomicInteger();
    private volatile RuntimeException failure;

    @Override
    public ModelEmbedResponse embed(ModelCall call, List<String> texts) {
        embedCalls.incrementAndGet();
        if (failure != null) {
            throw failure;
        }
        List<float[]> vectors = texts.stream().map(text -> {
            float[] vector = new float[VECTOR_SIZE];
            vector[0] = 1f;
            return vector;
        }).toList();
        return new ModelEmbedResponse(vectors, call.model(), new Usage(0, 0, 0, null));
    }

    @Override
    public ModelChatResponse chat(ModelCall call, List<ChatMessage> messages) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ModelChatResponse stream(ModelCall call, Consumer<String> onToken, List<ChatMessage> messages) {
        throw new UnsupportedOperationException();
    }

    void failWith(RuntimeException failure) {
        this.failure = failure;
    }

    void reset() {
        failure = null;
        embedCalls.set(0);
    }

    int embedCalls() {
        return embedCalls.get();
    }
}
