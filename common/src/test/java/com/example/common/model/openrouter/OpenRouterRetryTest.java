package com.example.common.model.openrouter;

import com.example.common.model.ChatMessage;
import com.example.common.model.ModelCall;
import com.example.common.model.ModelCallLogger;
import com.example.common.model.ModelChatResponse;
import com.example.common.model.Role;
import com.openai.core.http.Headers;
import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.RateLimitException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// No network: a mocked ChatModel throws the SDK's real exceptions
class OpenRouterRetryTest {

    private static final ModelCall CALL = new ModelCall(null, "test", "some/model");
    private static final List<ChatMessage> MESSAGES = List.of(new ChatMessage(Role.USER, "hi"));

    private final ChatModel chatModel = mock(ChatModel.class);
    private final ModelCallLogger logger = mock(ModelCallLogger.class);
    // 1 ms backoff keeps the test fast; production uses 1 s
    private final OpenRouterModelGateway gateway = new OpenRouterModelGateway(
            chatModel, mock(EmbeddingModel.class), logger, new OpenRouterRetry(4, 1));

    private static RateLimitException rateLimited() {
        return RateLimitException.builder().headers(Headers.builder().build()).build();
    }

    private static InternalServerException serverError() {
        return InternalServerException.builder().statusCode(503).headers(Headers.builder().build()).build();
    }

    private static BadRequestException badRequest() {
        return BadRequestException.builder().headers(Headers.builder().build()).build();
    }

    private static ChatResponse reply(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private void verifyRows(String status, int count) {
        verify(logger, times(count)).createModelCall(eq(CALL), eq("openrouter"), any(), eq(status), any(), anyInt());
    }

    @Test
    void retryableStatuses() {
        assertThat(OpenRouterRetry.isRetryableStatus(429)).isTrue();
        assertThat(OpenRouterRetry.isRetryableStatus(500)).isTrue();
        assertThat(OpenRouterRetry.isRetryableStatus(503)).isTrue();
        assertThat(OpenRouterRetry.isRetryableStatus(400)).isFalse();
        assertThat(OpenRouterRetry.isRetryableStatus(401)).isFalse();
        assertThat(OpenRouterRetry.isRetryableStatus(404)).isFalse();
    }

    @Test
    void rateLimit_isRetryable_evenWhenWrapped() {
        assertThat(OpenRouterRetry.isRetryable(rateLimited())).isTrue();
        assertThat(OpenRouterRetry.isRetryable(new IllegalStateException("wrapped", rateLimited()))).isTrue();
        assertThat(OpenRouterRetry.isRetryable(badRequest())).isFalse();
        assertThat(OpenRouterRetry.isRetryable(new IllegalStateException("unrelated"))).isFalse();
    }

    @Test
    void chat_twoRateLimitsThenSuccess_succeeds_andLogsEveryAttempt() {
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(rateLimited())
                .thenThrow(rateLimited())
                .thenReturn(reply("hello"));

        ModelChatResponse response = gateway.chat(CALL, MESSAGES);

        assertThat(response.chat()).isEqualTo("hello");
        verify(chatModel, times(3)).call(any(Prompt.class));
        verifyRows("error", 2);   // retries cost money, so they stay visible
        verifyRows("ok", 1);
    }

    @Test
    void chat_badRequest_isNotRetried() {
        when(chatModel.call(any(Prompt.class))).thenThrow(badRequest());

        assertThatThrownBy(() -> gateway.chat(CALL, MESSAGES)).isInstanceOf(BadRequestException.class);
        verify(chatModel, times(1)).call(any(Prompt.class));
        verifyRows("error", 1);
    }

    @Test
    void chat_givesUpAfterMaxAttempts() {
        when(chatModel.call(any(Prompt.class))).thenThrow(serverError());

        assertThatThrownBy(() -> gateway.chat(CALL, MESSAGES)).isInstanceOf(InternalServerException.class);
        verify(chatModel, times(4)).call(any(Prompt.class));
        verifyRows("error", 4);
    }

    @Test
    void stream_failingBeforeAnyToken_isRetried() {
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.error(rateLimited()))
                .thenReturn(Flux.just(reply("Hel"), reply("lo")));
        List<String> pieces = new CopyOnWriteArrayList<>();

        ModelChatResponse response = gateway.stream(CALL, pieces::add, MESSAGES);

        assertThat(response.chat()).isEqualTo("Hello");
        assertThat(pieces).containsExactly("Hel", "lo");
        verifyRows("error", 1);
        verifyRows("ok", 1);
    }

    @Test
    void stream_failingAfterTokens_isNotRetried_andThrowsTheRealError() {
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.concat(Flux.just(reply("Hel")), Flux.error(serverError())));
        List<String> pieces = new CopyOnWriteArrayList<>();

        assertThatThrownBy(() -> gateway.stream(CALL, pieces::add, MESSAGES))
                .isInstanceOf(InternalServerException.class);
        // "Hel" was already shown; a retry would show it again
        assertThat(pieces).containsExactly("Hel");
        verify(chatModel, times(1)).stream(any(Prompt.class));
        verifyRows("error", 1);
    }
}
