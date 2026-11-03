package com.example.common.model.openrouter;

import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * The only retry layer for model calls (the SDK's and Spring AI's own retries are switched off),
 * so every attempt goes through the gateway and gets its own model_calls row.
 */
@Component
public class OpenRouterRetry {

    private final Retry retry;

    public OpenRouterRetry(@Value("${rag.models.retry.max-attempts:4}") int maxAttempts,
                           @Value("${rag.models.retry.initial-backoff-ms:1000}") long initialBackoffMs) {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(maxAttempts)
                // 1 s, 2 s, 4 s: retrying at once would just hit the same rate limit again
                .intervalFunction(IntervalFunction.ofExponentialBackoff(initialBackoffMs, 2))
                .retryOnException(OpenRouterRetry::isRetryable)
                .build();
        this.retry = Retry.of("openrouter", config);
    }

    public <T> T execute(Supplier<T> attempt) {
        return retry.executeSupplier(attempt);
    }

    /**
     * Retry only what can succeed a moment later: 429, 5xx and network errors.
     * 400/401/403/404 fail the same way every time. Spring AI may wrap the SDK
     * exception, so the whole cause chain is checked.
     */
    static boolean isRetryable(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof PartialStreamException) {
                return false;   // tokens already reached the caller; a retry would repeat them
            }
            if (t instanceof OpenAIServiceException service) {
                return isRetryableStatus(service.statusCode());
            }
            if (t instanceof OpenAIIoException || t instanceof OpenAIRetryableException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    static boolean isRetryableStatus(int status) {
        return status == 429 || status >= 500;
    }

    /** A stream that failed after some tokens were already delivered: never retried. */
    static final class PartialStreamException extends RuntimeException {
        PartialStreamException(RuntimeException cause) {
            super(cause);
        }
    }
}
