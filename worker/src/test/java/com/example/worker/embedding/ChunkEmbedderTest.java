package com.example.worker.embedding;

import com.example.common.model.ModelCall;
import com.example.common.model.ModelEmbedResponse;
import com.example.common.model.ModelGateway;
import com.example.common.model.Usage;
import com.example.worker.chunking.TextChunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChunkEmbedderTest {

    private static final UUID TENANT = UUID.randomUUID();

    private final List<ModelCall> calls = new ArrayList<>();
    private final List<List<String>> batches = new ArrayList<>();
    private int nextVector;

    // Each vector holds a running number, so the test can see the order they came back in.
    private final ModelGateway gateway = recordingGateway();

    private ModelGateway recordingGateway() {
        ModelGateway mock = mock(ModelGateway.class);
        when(mock.embed(any(), any())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(1);
            calls.add(invocation.getArgument(0));
            batches.add(texts);
            List<float[]> vectors = texts.stream().map(text -> new float[]{nextVector++}).toList();
            return new ModelEmbedResponse(vectors, "m", new Usage(0, 0, 0, null));
        });
        return mock;
    }

    private static List<TextChunk> chunks(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> new TextChunk(i, "", "metin " + i, 2))
                .toList();
    }

    @Test
    void chunksAreSentInBatches_andTheLastOneMayBeSmaller() {
        new ChunkEmbedder(2, gateway).embed(chunks(5), "m", TENANT);

        assertThat(batches).extracting(List::size).containsExactly(2, 2, 1);
    }

    @Test
    void vectorsComeBackInChunkOrder() {
        List<float[]> vectors = new ChunkEmbedder(2, gateway).embed(chunks(5), "m", TENANT);

        assertThat(vectors).extracting(vector -> vector[0]).containsExactly(0f, 1f, 2f, 3f, 4f);
    }

    @Test
    void theHeadingIsEmbeddedWithTheContent_whenThereIsOne() {
        new ChunkEmbedder(2, gateway).embed(List.of(
                new TextChunk(0, "Kurulum > Docker", "Yapılandırma", 1),
                new TextChunk(1, "", "Başlıksız", 1)), "m", TENANT);

        assertThat(batches.getFirst()).containsExactly("Kurulum > Docker\n\nYapılandırma", "Başlıksız");
    }

    @Test
    void everyCallNamesTheModelTenantAndPurpose() {
        new ChunkEmbedder(2, gateway).embed(chunks(3), "some/model", TENANT);

        assertThat(calls).hasSize(2).allSatisfy(call -> {
            assertThat(call.model()).isEqualTo("some/model");
            assertThat(call.tenantId()).isEqualTo(TENANT);
            assertThat(call.purpose()).isEqualTo("ingestion-embedding");
        });
    }

    @Test
    void aShortResponse_fails_ratherThanPairingVectorsWithTheWrongChunks() {
        ModelGateway shortResponse = mock(ModelGateway.class);
        when(shortResponse.embed(any(), any()))
                .thenReturn(new ModelEmbedResponse(List.of(new float[]{1f}), "m", new Usage(0, 0, 0, null)));

        assertThatThrownBy(() -> new ChunkEmbedder(2, shortResponse).embed(chunks(2), "m", TENANT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected 2 vectors, got 1");
    }

    @Test
    void noChunks_makesNoCalls() {
        assertThat(new ChunkEmbedder(2, gateway).embed(List.of(), "m", TENANT)).isEmpty();
        assertThat(calls).isEmpty();
    }
}
